# engine-iso-perf-framework

Welcome to the Engine Isolated Performance Framework!

### Onboarding Job
https://core.jenkins.hyperloop.sonynei.net/shared-tools/job/workflow-pipelines/job/onboarding/job/generate-iso-perf-test-jobs/

The onboarding job will generate iso-perf-test-jobs in the local controller based on the repo's [engine-cd-configuration](https://github.sie.sony.com/SIE/engine-cd-configurations).

### Full Onboarding Details
https://confluence.sie.sony.com/x/v5XQdQ

### Pipeline Definitions
This primarily utilizes existing Unified CICD configuration. 
- For CI configuration, see: [engine-ci-framework](https://github.sie.sony.com/SIE/engine-ci-framework/blob/master/README.md).
- For CD Configuration, see: [engine-cd-configurations](https://github.sie.sony.com/SIE/engine-cd-configurations/blob/master/README.md).
- The generated iso-perf-test job will consume a new helm chart defined at /repo/performance-test/helm/performance-test, please see [engine-performance-test](https://github.sie.sony.com/SIE/engine-performance-test/blob/main/README.md).

### CI
For each PR creation or main merge, the [engine iso perf framework test jobs](https://core.jenkins.hyperloop.sonynei.net/gaminglife-core/job/iso-perf-test-jobs/job/engine-iso-perf-framework/) validations will be executed for:
- [Gradle](https://core.jenkins.hyperloop.sonynei.net/gaminglife-core/job/iso-perf-test-jobs/job/engine-iso-perf-framework-test-jobs/job/gradle-catalyst-example/)
- [Maven](https://core.jenkins.hyperloop.sonynei.net/gaminglife-core/job/iso-perf-test-jobs/job/engine-iso-perf-framework-test-jobs/job/catalyst-example/)
- [Go](https://core.jenkins.hyperloop.sonynei.net/gaminglife-core/job/iso-perf-test-jobs/job/engine-iso-perf-framework-test-jobs/job/engine-go-pipeline-example/)

The PR-# is consumed as the env.BRANCH_NAME value for the engine-iso-perf-framework test job and the invoked test jobs receive the LIB_VERSION parameter so that the tests are executed on the new branch.
