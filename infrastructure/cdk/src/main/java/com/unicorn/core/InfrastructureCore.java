package com.unicorn.core;

import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.ISecurityGroup;
import software.amazon.awscdk.services.ec2.SecurityGroupProps;
import software.amazon.awscdk.services.events.EventBus;
import software.amazon.awscdk.services.iam.ArnPrincipal;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.rds.AuroraPostgresClusterEngineProps;
import software.amazon.awscdk.services.rds.ServerlessV2ClusterInstanceProps;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.rds.AuroraPostgresEngineVersion;
import software.amazon.awscdk.services.rds.Credentials;
import software.amazon.awscdk.services.rds.ClusterInstance;
import software.amazon.awscdk.services.rds.DatabaseCluster;
import software.amazon.awscdk.services.rds.DatabaseClusterEngine;
import software.amazon.awscdk.services.rds.DatabaseSecret;
import software.amazon.awscdk.services.ssm.ParameterTier;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.SecretValue;
import software.amazon.awscdk.SecretsManagerSecretOptions;
import software.constructs.Construct;

import java.util.List;

public class InfrastructureCore extends Construct {

    private final DatabaseSecret databaseSecret;
    private final DatabaseCluster database;
    private final EventBus eventBridge;
    private final IVpc vpc;
    private final ISecurityGroup applicationSecurityGroup;
    private final StringParameter paramDBConnectionString;
    private final Secret secretPassword;
    private final StringParameter paramBucketName;
    private final Bucket lambdaCodeBucket;

    public InfrastructureCore(final Construct scope, final String id, final IVpc vpc) {
        super(scope, id);

        this.vpc = vpc;
        databaseSecret = createDatabaseSecret();
        database = createDatabase(vpc, databaseSecret);
        eventBridge = createEventBus();
        applicationSecurityGroup = new SecurityGroup(this, "ApplicationSecurityGroup",
            SecurityGroupProps
                .builder()
                .securityGroupName("unicornstore-application-sg")
                .vpc(vpc)
                .allowAllOutbound(true)
                .build());

        paramDBConnectionString = createParamDBConnectionString();
        secretPassword = createSecretPassword();
        lambdaCodeBucket = createLambdaCodeBucket();
        paramBucketName = createParamBucketName();
        createRolesLambdaBedrock();
    }

    private Bucket createLambdaCodeBucket() {
        var lambdaCodeBucket = Bucket.Builder
            .create(this, "LambdaCodeBucket")
            .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
            .enforceSsl(true)
            .removalPolicy(RemovalPolicy.DESTROY)
            .build();
        return lambdaCodeBucket;
    }


    private StringParameter createParamBucketName() {
        return StringParameter.Builder.create(this, "SsmParameterUnicornStoreBucketName")
            .allowedPattern(".*")
            .description("Lambda code bucket name")
            .parameterName("unicornstore-lambda-bucket-name")
            .stringValue(lambdaCodeBucket.getBucketName())
            .tier(ParameterTier.STANDARD)
            .build();
    }

    public StringParameter getParamBucketName() {
        return paramBucketName;
    }

    private void createRolesLambdaBedrock() {
        ServicePrincipal lambdaServicePrincipal = new ServicePrincipal("lambda.amazonaws.com");

        var unicornStoreLambdaBedrockRole = Role.Builder.create(this, "UnicornStoreLambdaBedrockRole")
            .roleName("unicornstore-lambda-bedrock-role")
            .assumedBy(lambdaServicePrincipal.withSessionTags())
            .build();
        unicornStoreLambdaBedrockRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "UnicornStoreLambdaBedrockRole-" + "AmazonBedrockLimitedAccess",
            "arn:aws:iam::aws:policy/AmazonBedrockLimitedAccess"));
        unicornStoreLambdaBedrockRole.addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(this,
            "UnicornStoreLambdaBedrockRole-" + "AWSLambdaVPCAccessExecutionRole",
            "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"));

        getEventBridge().grantPutEventsTo(unicornStoreLambdaBedrockRole);
        getDatabaseSecret().grantRead(unicornStoreLambdaBedrockRole);
        getParamDBConnectionString().grantRead(unicornStoreLambdaBedrockRole);
    }

    private EventBus createEventBus() {
        return EventBus.Builder.create(this, "UnicornEventBus")
                .eventBusName("unicorns")
                .build();
    }

    private SecurityGroup createDatabaseSecurityGroup(IVpc vpc) {
        var databaseSecurityGroup = SecurityGroup.Builder.create(this, "DatabaseSG")
                .securityGroupName("unicornstore-db-sg")
                .allowAllOutbound(false)
                .vpc(vpc)
                .build();

        databaseSecurityGroup.addIngressRule(
                Peer.ipv4("10.0.0.0/16"),
                Port.tcp(5432),
                "Allow Database Traffic from local network");

        return databaseSecurityGroup;
    }

    private DatabaseCluster createDatabase(IVpc vpc, DatabaseSecret databaseSecret) {

        var databaseSecurityGroup = createDatabaseSecurityGroup(vpc);

        var dbCluster = DatabaseCluster.Builder.create(this, "UnicornStoreDatabase")
            .engine(DatabaseClusterEngine.auroraPostgres(
                AuroraPostgresClusterEngineProps.builder().version(AuroraPostgresEngineVersion.VER_16_4).build()))
            .serverlessV2MinCapacity(0.5)
            .serverlessV2MaxCapacity(4)
            .writer(ClusterInstance.serverlessV2("UnicornStoreDatabaseWriter", ServerlessV2ClusterInstanceProps.builder()
                .instanceIdentifier("unicornstore-db-writer")
                .autoMinorVersionUpgrade(true)
                .build()))
            .enableDataApi(true)
            .defaultDatabaseName("unicorns")
            .clusterIdentifier("unicornstore-db-cluster")
            .instanceIdentifierBase("unicornstore-db-instance")
            .vpc(vpc)
            .vpcSubnets(SubnetSelection.builder()
                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                .build())
            .securityGroups(List.of(databaseSecurityGroup))
            .credentials(Credentials.fromSecret(databaseSecret))
            .removalPolicy(RemovalPolicy.DESTROY)
            .build();

            return dbCluster;
    }

    private DatabaseSecret createDatabaseSecret() {
        return DatabaseSecret.Builder
            .create(this, "postgres")
            .secretName("unicornstore-db-secret")
            .username("postgres").build();
    }

    private Secret createSecretPassword() {
        // Separate password value for services which cannot get specific field from Secret json
        return Secret.Builder.create(this, "dbSecretPassword")
            .secretName("unicornstore-db-password-secret")
            .secretStringValue(SecretValue.secretsManager(databaseSecret.getSecretName(),
                SecretsManagerSecretOptions.builder().jsonField("password").build()))
            .build();
    }

    public Secret getSecretPassword() {
        return secretPassword;
    }

    private StringParameter createParamDBConnectionString() {
        return StringParameter.Builder.create(this, "SsmParameterDBConnectionString")
            .allowedPattern(".*")
            .description("Database Connection String")
            .parameterName("unicornstore-db-connection-string")
            .stringValue(getDBConnectionString())
            .tier(ParameterTier.STANDARD)
            .build();
    }

    public String getDBConnectionString(){
        return "jdbc:postgresql://" + database.getClusterEndpoint().getHostname() + ":5432/unicorns";
    }

    public StringParameter getParamDBConnectionString() {
        return paramDBConnectionString;
    }

    public EventBus getEventBridge() {
        return eventBridge;
    }

    public IVpc getVpc() {
        return vpc;
    }

    public ISecurityGroup getApplicationSecurityGroup() {
        return applicationSecurityGroup;
    }

    public String getDatabaseSecretString(){
        return databaseSecret.secretValueFromJson("password").toString();
    }

    public DatabaseSecret getDatabaseSecret(){
        return databaseSecret;
    }

    public DatabaseCluster getDatabase() {
        return database;
    }
}
