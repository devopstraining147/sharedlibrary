package com.sony.sie.cicd.cd.pipelines

import com.sony.sie.cicd.helpers.annotations.CDScope
import com.sony.sie.cicd.helpers.annotations.StageOrder
import com.sony.sie.cicd.helpers.annotations.StageLabel
import com.sony.sie.cicd.cd.utilities.DeployUtils
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import com.sony.sie.cicd.helpers.utilities.JenkinsSteps
import com.sony.sie.cicd.cd.utilities.SecurityUtils

abstract class BaseCDPipeline extends JenkinsSteps {
    final JenkinsUtils jenkinsUtils = new JenkinsUtils()
    final DeployUtils deployUtils = new DeployUtils()
    def deployProcessor
    def serviceNowUtils
    def deployConfiguration
    def basePipelineClassList = [BaseCDPipeline.class]
    String securityApprovalFlag = "auto"

    BaseCDPipeline(def deployConfiguration){
        this.deployConfiguration = deployConfiguration
    }

    abstract void deploymentSetup()

    @StageOrder(id=100)
    @CDScope
    @StageLabel(value="")
    void deploymentProcess() {
        deployProcessor.executeDeployment(deployConfiguration, securityApprovalFlag)
    }
}

