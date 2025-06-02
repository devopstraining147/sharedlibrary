package com.sony.sie.cicd.helpers.enums

enum StageName {
    ISOPERF_ORCH("isoPerfOrch"),
    DEPLOY_APP("deployApp"),
    DEPLOY_DEPENDENCIES("deployDependencies"),
    CLEANUP_RESOURCES("cleanupApp"),
    BUILD_APP("buildApp"),
    TEST_JOB("testJob");

    public String formattedName;

    StageName(String formattedName) {
        this.formattedName = formattedName;
    }
}