# DSP Assignment 1 — OCR in the Cloud

Distributed OCR on AWS: a local application, a manager, and workers communicating via SQS and S3.

## Project layout

| Module | Jar | Runs on | Role |
|---|---|---|---|
| `common` | (library) | — | AWS clients, config, SQS message types |
| `local-app` | `local-app/target/local-app.jar` | your machine | Uploads input, waits for result, writes HTML |
| `manager` | `manager/target/manager.jar` | EC2 | Splits tasks, scales workers, builds summaries |
| `worker` | `worker/target/worker.jar` | EC2 | Downloads image, runs OCR, reports result |

## Build

```bash
mvn clean package
```

## Run

```bash
java -jar local-app/target/local-app.jar inputFileName outputFileName manager-clients-n worker-files-n [terminate]
```

_TODO: names & IDs, AMI + instance type, run time, `n` used, design notes (queues, scalability, fault tolerance, threads, security, termination)._
