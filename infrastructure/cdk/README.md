# Welcome to the Unicorn Store CDK Java project!

This project is used to deploy the needed infrastructure resources to run the Unicorn Store.
Please follow the workshop instructions on how to properly configure the environment.

```bash
# go to infrastructure folder
cd infrastructure
# build cdk project and generate unicornstore-stack
npm run generate-unicornstore-stack
# copy /infrastructure/cfn/unicornstore-stack.yaml to java-on-amazon-eks and java-on-aws-immersion-day folder one level up
npm run sync-workshops-stacks
# copy cdk/src/main/resources/iam-policy.json to java-on-amazon-eks and java-on-aws-immersion-day folder one level up
npm run sync-workshops-policy
```

It is a [Maven](https://maven.apache.org/) based project, so you can open this project with any Maven compatible Java IDE to build and run tests.

## Useful commands

 * `mvn package`     compile and run tests
 * `cdk ls`          list all stacks in the app
 * `cdk synth`       emits the synthesized CloudFormation template
 * `cdk deploy`      deploy this stack to your default AWS account/region
 * `cdk diff`        compare deployed stack with current state
 * `cdk docs`        open CDK documentation

Enjoy!
