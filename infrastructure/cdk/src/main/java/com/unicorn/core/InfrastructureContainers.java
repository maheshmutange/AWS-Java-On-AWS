package com.unicorn.core;

import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.apprunner.alpha.VpcConnector;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.constructs.Construct;

import java.util.List;

// Additional infrastructure for Containers modules of Java on AWS Immersion Day
public class InfrastructureContainers extends Construct {

    private final InfrastructureCore infrastructureCore;

    public InfrastructureContainers(final Construct scope, final String id,
        final InfrastructureCore infrastructureCore) {
        super(scope, id);

        // Get previously created infrastructure construct
        this.infrastructureCore = infrastructureCore;

        createUnicornStoreSpringEcr();
        createVpcConnector();
        createRolesAppRunner();
        createRolesEcs();
    }

    private Repository createUnicornStoreSpringEcr() {
        return Repository.Builder.create(this, "UnicornStoreSpringEcr")
            .repositoryName("unicorn-store-spring")
            .imageScanOnPush(false)
            .removalPolicy(RemovalPolicy.DESTROY)
            .emptyOnDelete(true)  // This will force delete all images when repository is deleted
            .build();
    }

    private void createVpcConnector() {
        VpcConnector.Builder.create(this, "UnicornStoreVpcConnector")
            .vpc(infrastructureCore.getVpc())
            .vpcSubnets(SubnetSelection.builder()
                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                .build())
            .vpcConnectorName("unicornstore-vpc-connector")
            .build();
    }

    private void createRolesAppRunner() {
        var unicornStoreApprunnerRole = Role.Builder.create(this, "UnicornStoreApprunnerRole")
            .roleName("unicornstore-apprunner-role")
            .assumedBy(new ServicePrincipal("tasks.apprunner.amazonaws.com")).build();
        unicornStoreApprunnerRole.addToPolicy(PolicyStatement.Builder.create()
            .actions(List.of("xray:PutTraceSegments"))
            .resources(List.of("*"))
            .build());
        infrastructureCore.getEventBridge().grantPutEventsTo(unicornStoreApprunnerRole);
        infrastructureCore.getDatabaseSecret().grantRead(unicornStoreApprunnerRole);
        infrastructureCore.getSecretPassword().grantRead(unicornStoreApprunnerRole);
        infrastructureCore.getParamDBConnectionString().grantRead(unicornStoreApprunnerRole);

        var appRunnerECRAccessRole = Role.Builder.create(this, "UnicornStoreApprunnerEcrAccessRole")
            .roleName("unicornstore-apprunner-ecr-access-role")
            .assumedBy(new ServicePrincipal("build.apprunner.amazonaws.com")).build();
        appRunnerECRAccessRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "UnicornStoreApprunnerEcrAccessRole-" + "AWSAppRunnerServicePolicyForECRAccess",
            "arn:aws:iam::aws:policy/service-role/AWSAppRunnerServicePolicyForECRAccess"));

        // // Create the App Runner service-linked role
        // Long tsLong = System.currentTimeMillis()/1000;
        // String timestamp = tsLong.toString();
        // CfnServiceLinkedRole appRunnerServiceLinkedRole = CfnServiceLinkedRole.Builder.create(this, "AppRunnerServiceLinkedRole")
        //     .awsServiceName("apprunner.amazonaws.com")
        //     .description("Service-linked role for AWS App Runner service")
        //     .customSuffix(timestamp)
        //     .build();
    }

    private void createRolesEcs() {
        var AWSOpenTelemetryPolicy = PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(List.of("logs:PutLogEvents", "logs:CreateLogGroup", "logs:CreateLogStream",
                    "logs:DescribeLogStreams", "logs:DescribeLogGroups",
                    "logs:PutRetentionPolicy", "xray:PutTraceSegments",
                    "xray:PutTelemetryRecords", "xray:GetSamplingRules",
                    "xray:GetSamplingTargets", "xray:GetSamplingStatisticSummaries",
                    "cloudwatch:PutMetricData", "ssm:GetParameters"))
            .resources(List.of("*")).build();

        var unicornStoreEscTaskRole = Role.Builder.create(this, "UnicornStoreEcsTaskRole")
            .roleName("unicornstore-ecs-task-role")
            .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com")).build();
        unicornStoreEscTaskRole.addToPolicy(PolicyStatement.Builder.create()
            .actions(List.of("xray:PutTraceSegments"))
            .resources(List.of("*"))
            .build());
        unicornStoreEscTaskRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "UnicornStoreEcsTaskRole-" + "CloudWatchLogsFullAccess",
            "arn:aws:iam::aws:policy/CloudWatchLogsFullAccess"));
        unicornStoreEscTaskRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "UnicornStoreEcsTaskRole-" + "AmazonSSMReadOnlyAccess",
            "arn:aws:iam::aws:policy/AmazonSSMReadOnlyAccess"));
        unicornStoreEscTaskRole.addToPolicy(AWSOpenTelemetryPolicy);

        infrastructureCore.getEventBridge().grantPutEventsTo(unicornStoreEscTaskRole);
        infrastructureCore.getDatabaseSecret().grantRead(unicornStoreEscTaskRole);
        infrastructureCore.getSecretPassword().grantRead(unicornStoreEscTaskRole);
        infrastructureCore.getParamDBConnectionString().grantRead(unicornStoreEscTaskRole);

        Role unicornStoreEscTaskExecutionRole = Role.Builder.create(this, "UnicornStoreEcsTaskExecutionRole")
            .roleName("unicornstore-ecs-task-execution-role")
            .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com")).build();
        unicornStoreEscTaskExecutionRole.addToPolicy(PolicyStatement.Builder.create()
            .actions(List.of("logs:CreateLogGroup"))
            .resources(List.of("*"))
            .build());
        unicornStoreEscTaskExecutionRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "UnicornStoreEcsTaskExecutionRole-" + "AmazonECSTaskExecutionRolePolicy",
            "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"));
        unicornStoreEscTaskExecutionRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "UnicornStoreEcsTaskExecutionRole-" + "CloudWatchLogsFullAccess",
            "arn:aws:iam::aws:policy/CloudWatchLogsFullAccess"));
        unicornStoreEscTaskExecutionRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "UnicornStoreEcsTaskExecutionRole-" + "AmazonSSMReadOnlyAccess",
            "arn:aws:iam::aws:policy/AmazonSSMReadOnlyAccess"));
        unicornStoreEscTaskExecutionRole.addToPolicy(AWSOpenTelemetryPolicy);

        infrastructureCore.getEventBridge().grantPutEventsTo(unicornStoreEscTaskExecutionRole);
        infrastructureCore.getDatabaseSecret().grantRead(unicornStoreEscTaskExecutionRole);
        infrastructureCore.getSecretPassword().grantRead(unicornStoreEscTaskExecutionRole);
    }

}
