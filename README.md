# DSP Assignment 1 — OCR in the Cloud

Distributed OCR on AWS: a local application, managers and workers that communicate only through SQS and S3.

**Students:** `<NAME 1>` (`<ID 1>`), `<NAME 2>` (`<ID 2>`)

## Run results

| | |
|---|---|
| AMI | `ami-0d3c2ca30017afda9`: Ubuntu 26.04 + OpenJDK (Ubuntu default) + tesseract-ocr 5 (eng) + AWS CLI v2, built by `scripts/build-ami.sh`, which also runs a real OCR test with `worker.jar` before saving the image |
| Instance type | `t3.small` (manager and workers) |
| Input | `input-sample.txt`, the course's sample (24 URLs, 10 different; 5 of them no longer load) |
| `n` (worker-files-n) | 5 → 5 workers |
| `manager-clients-n` | 2 |
| Total run time | **1:33** from start of the local app to the HTML written, with `terminate`; manager and workers launched from scratch |
| Result | 24 images: 12 with OCR text, 12 with the error (9 × HTTP 404, 3 × host not found) |
| Output | `output/output-sample.html` in the submission zip |

## How to run

**Prerequisites:** JDK 17, Maven, AWS CLI v2, a running AWS Academy Learner Lab.

```bash
# 1. Lab credentials -> ~/.aws profile "dsp-lab" (copy AWS Details -> AWS CLI from the lab first)
scripts/lab-creds.sh set

# 2. One time: build the AMI (~10 min). Writes the AMI id into Config.java.
scripts/build-ami.sh

# 3. After every code change: build the jars and upload manager.jar + worker.jar to S3
scripts/build-and-upload.sh

# 4. Run (as many local apps at the same time as you like)
java -jar local-app/target/local-app.jar inputFileName outputFileName manager-clients-n worker-files-n [terminate]

# 5. Verify that nothing is left running; remove leftovers (asks before deleting)
scripts/cleanup.sh --check
scripts/cleanup.sh
```

All AWS access uses the `dsp-lab` profile, **even if `AWS_PROFILE` is set in your shell**, so nothing ever runs in
another account by accident (`DSP_AWS_PROFILE=<name>` to override on purpose).

**Local test without EC2** (spec's "run the manager, the local app and a worker on your computer"):

```bash
java -jar manager/target/manager.jar 2 --local test1                 # terminal 1
TESSDATA_PREFIX=/opt/homebrew/share/tessdata \
  java -jar worker/target/worker.jar test1 --local                   # terminal 2
DSP_LOCAL_MANAGER=test1 java -jar local-app/target/local-app.jar input.txt out.html 2 5 terminate
```

**Submission zip:** `scripts/package-submission.sh <output.html>` → `dsp-ex1-submission.zip`
(sources, class files, README, scripts and the output; no libraries, no credentials).

## How it works

```
 Local app ──NEW_TASK / TERMINATE──▶ requests-<mgr> ──▶ Manager ──ImageTask×N──▶ tasks-<mgr> ──▶ Workers
     ▲                                                      │  ▲                                  │
     │                                                      │  └──── OcrResult ── results-<mgr> ◀─┘
     └──────────── TaskDone ── reply-<taskId> ◀─────────────┘
 S3: inputs/<taskId>.txt · results/<taskId>/<index>.txt · summaries/<taskId>.jsonl · jars/
```

1. The **local app** uploads its input file to S3, finds a manager tagged `Accepting=true` (or launches one), sends
   NEW_TASK to that manager's request queue, and waits on its own reply queue.
2. The **manager** downloads the input, sends one `ImageTask` per URL to its tasks queue, and starts workers.
3. Each **worker** takes one `ImageTask` at a time, downloads the image, runs Tesseract, uploads the text to
   `results/<taskId>/<index>.txt`, sends an `OcrResult` (text key, or a short error) and only then deletes the task.
4. The manager records results per job. When a job has all its results, it writes the summary
   (`summaries/<taskId>.jsonl`, one result per line in input order) and sends `TaskDone` to that client.
5. The local app downloads the summary and the texts (10 threads), writes the HTML (linked image + text, or
   link + error), deletes the job's files from S3 and its reply queue, and sends TERMINATE if asked.

### Queues

| Message (spec) | Queue | Owner | Why |
|---|---|---|---|
| new task / terminate: app → manager | `dsp-ex1-requests-<managerId>` | manager | Each manager has its own, so a request reaches exactly the manager the app chose. |
| new image task: manager → workers | `dsp-ex1-tasks-<managerId>` | manager | All of one manager's workers pull from it; no message is assigned to a specific worker. |
| done OCR: worker → manager | `dsp-ex1-results-<managerId>` | manager | Results reach the manager that holds the job's state. |
| done: manager → app | `dsp-ex1-reply-<taskId>` | each local app | A client only ever receives its own answer: no filtering, no waiting on other clients. |

`<managerId>` is the manager's EC2 instance id. The app creates the manager's request queue itself when it
launches one, so its job waits in the queue while the manager boots. Whoever creates a queue deletes it.

### EC2 tags

| Instance | Tags |
|---|---|
| Manager | `Role=manager`; `Accepting=true/false`, set **by the manager**: false when it serves `manager-clients-n` clients or got TERMINATE. |
| Worker | `Role=worker`, `ManagerId=<managerId>`. |

An *available* manager (spec) is an active instance with `Role=manager, Accepting=true`. The manager gets
`manager-clients-n` at launch from the app that started it, so the limit is a property of the manager ("maximum
clients per manager").

### Manager threads

A fixed number of threads, however many clients (thread-per-client would not scale to millions of clients):

| Thread | # | Job |
|---|---|---|
| Request listener | 1 | Reads its request queue one message at a time, always (TERMINATE arrives there too). |
| Job pool | 3 | Downloads an input, queues its ImageTasks in SQS batches of 10, scales workers. Several clients are set up in parallel. |
| Result listeners | 3 | Receive up to 10 results per poll, record them, delete them only after recording. The thread that completes a job writes the summary and replies. |
| Monitor | 1 | Every 30s re-checks the worker count, so dead workers are replaced even if no new client arrives. After 5 minutes with no jobs it terminates the workers (the manager stays up for new clients). |

Jobs are entries in a `ConcurrentHashMap`; a job's completion is detected exactly once (`putIfAbsent` per index plus
a compare-and-set), so duplicate results and concurrent listeners are harmless.

### Worker scaling

Workers needed = Σ over active jobs of ⌈images left / worker-files-n⌉ (each client brings its own n). With k running,
the manager launches needed − k, never above **9 instances in the region** (managers included). The spec's cap is 19,
but the Learner Lab allows only 9 running instances at once and terminates any extra ones. Workers are never
assigned work: they all pull from one queue, so a fast worker simply takes more messages. Workers are stopped on
TERMINATE, as the spec defines, and also when the manager has had no jobs for 5 minutes, so idle workers don't use
the lab budget; a later job just launches new ones. The manager itself stays up: it is one instance, and stopping
it could race with a client that just picked it.

### Termination

TERMINATE → the manager sets `Accepting=false`; any NEW_TASK that still reaches it gets a `TaskDone` with an error
(the client exits instead of hanging). It waits until every accepted job has been answered, then terminates its
workers, deletes its three queues and terminates its own instance. `scripts/cleanup.sh --check` confirms nothing is
left.

## Mandatory questions

**Security.** No credentials anywhere in the code, jars, user-data or the submission. On EC2, instances get their
permissions from the IAM role `LabInstanceProfile`; locally the SDK reads the `dsp-lab` profile in `~/.aws`, which
`scripts/lab-creds.sh` writes with mode 600 (and clears from the clipboard). The bucket is private with public access
blocked. `package-submission.sh` refuses to zip anything that looks like a key. User-data only downloads a jar and runs
it.

**Scalability.** Clients are spread over managers by `manager-clients-n`; every manager has its own queues, workers and
state, so adding clients adds managers instead of loading one. Inside a manager the thread count is fixed and work per
client is a map entry. SQS and S3 scale on their own. The real ceiling is the lab's 9-instance limit; without it, a
million clients would mean more managers, each with its own worker pool. The local app's work (HTML, text downloads)
stays on the client.

**Persistence and failures.**

| Failure | Handling |
|---|---|
| Worker dies mid-image | The message was not deleted; it reappears after the visibility timeout (120s) and another worker takes it. |
| Worker instance dies | The monitor sees fewer workers than needed and launches replacements. |
| Worker JVM crashes | Its user-data runs Java in a restart loop. |
| Bad URL / not an image / OCR error | The worker sends a failure result with a short description; the HTML shows link + error. |
| AWS error while a worker reports | The task is not deleted, so it is retried. |
| Duplicate messages (SQS at-least-once) | Results are keyed by index; duplicates are ignored. Text keys are deterministic. |
| Manager dies | Its clients check every minute whether it is alive and resubmit once to another or a new manager (the input is still on S3). Its workers check every minute whether their manager is alive and terminate themselves. Its JVM is not restarted: the instance shuts down, so clients see it is gone. |
| Manager stalls | Its workers keep processing; its clients keep waiting (no timeout), see Limitations. |
| Bad input file / summary upload fails | The client gets a `TaskDone` with an error and exits. |

**Threads.** Used where the work is I/O-bound and independent: the manager's listeners and job pool, the local app's
text downloads. Not used in the worker: Tesseract is CPU-bound and a t3.small has little CPU, so parallelism comes
from more workers, not more threads per worker.

**Multiple clients.** Each client has its own task id, reply queue and S3 paths; managers handle their clients' jobs
in parallel. `<describe your concurrent-clients test here>`

**Workers working hard?** All workers of a manager pull from the same queue, one message at a time, so an idle worker
always takes the next image; nobody waits for a pre-assigned share.

**Role separation.** The local app: input, HTML and its own cleanup. The manager: splitting jobs, counting results,
scaling workers, summaries. It never opens an image or a text file. Workers: download + OCR only.

**Is anything waiting on another?** Only on queues, never on a specific component: the app waits for its reply
message, the manager for results, workers for tasks. A slow worker delays only the image it holds.

## Limitations

- Two apps checking tags at the same instant can both join a manager past its limit, or both launch one
  (the assignment allows assuming no such races).
- A manager that is alive but hung is not detected; its clients wait.
- After a manager crash, the job restarts from scratch on the new manager (images are OCR'd again).
