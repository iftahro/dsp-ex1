package dsp.ex1.common;

import software.amazon.awssdk.services.ec2.model.CreateTagsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.ShutdownBehavior;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Small wrapper over the EC2 calls the manager and the local app need. "Active" = pending or running. */
public final class Ec2Instances {

    private Ec2Instances() {}

    private static final Filter ACTIVE = Filter.builder()
            .name("instance-state-name").values("pending", "running").build();

    /** Active instances that carry all the given tags. */
    public static List<Instance> findActive(Map<String, String> tags) {
        List<Filter> filters = new ArrayList<>();
        filters.add(ACTIVE);
        tags.forEach((key, value) -> filters.add(Filter.builder().name("tag:" + key).values(value).build()));
        return describe(filters);
    }

    /** All active instances in the account, whatever their role. Used for the instance limit (Config.MAX_INSTANCES). */
    public static int countActive() {
        return describe(List.of(ACTIVE)).size();
    }

    public static boolean isActive(String instanceId) {
        return !describe(List.of(ACTIVE, Filter.builder().name("instance-id").values(instanceId).build())).isEmpty();
    }

    /**
     * Launches up to count instances (fewer if EC2 can't place them all) with the given tags and user-data.
     * shutdown -h now on the instance terminates it instead of only stopping it.
     */
    public static List<String> launch(int count, String userDataScript, Map<String, String> tags) {
        List<Tag> tagList = tags.entrySet().stream()
                .map(e -> Tag.builder().key(e.getKey()).value(e.getValue()).build())
                .toList();
        RunInstancesRequest request = RunInstancesRequest.builder()
                .imageId(Config.AMI_ID)
                .instanceType(InstanceType.fromValue(Config.INSTANCE_TYPE))
                .minCount(1)
                .maxCount(count)
                .iamInstanceProfile(IamInstanceProfileSpecification.builder().name(Config.INSTANCE_PROFILE).build())
                .instanceInitiatedShutdownBehavior(ShutdownBehavior.TERMINATE)
                .userData(Base64.getEncoder().encodeToString(userDataScript.getBytes(StandardCharsets.UTF_8)))
                .tagSpecifications(TagSpecification.builder().resourceType(ResourceType.INSTANCE).tags(tagList).build())
                .build();
        return AwsClients.EC2.runInstances(request).instances().stream().map(Instance::instanceId).toList();
    }

    public static void setTag(String instanceId, String key, String value) {
        AwsClients.EC2.createTags(CreateTagsRequest.builder()
                .resources(instanceId)
                .tags(Tag.builder().key(key).value(value).build())
                .build());
    }

    public static void terminate(List<String> instanceIds) {
        if (!instanceIds.isEmpty()) {
            AwsClients.EC2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(instanceIds).build());
        }
    }

    private static List<Instance> describe(List<Filter> filters) {
        // The paginator follows nextToken, so large accounts are still listed in full.
        return AwsClients.EC2.describeInstancesPaginator(DescribeInstancesRequest.builder().filters(filters).build())
                .reservations().stream()
                .flatMap(r -> r.instances().stream())
                .toList();
    }
}
