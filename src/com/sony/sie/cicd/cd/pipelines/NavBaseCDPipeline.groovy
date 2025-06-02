package com.sony.sie.cicd.cd.pipelines

import com.sony.sie.cicd.cd.deployProcessors.NavK8sDeployProcessor
import com.sony.sie.cicd.cd.utilities.NavServiceNow
import com.sony.sie.cicd.helpers.annotations.CDScope
import com.sony.sie.cicd.helpers.annotations.StageOrder
import com.sony.sie.cicd.helpers.annotations.StageLabel

abstract class NavBaseCDPipeline extends BaseCDPipeline {

    NavBaseCDPipeline(def deployConfiguration) {
        super(deployConfiguration)
    }

    void deploymentSetup() {
        deployProcessor = new NavK8sDeployProcessor()
        serviceNowUtils = new NavServiceNow(deployConfiguration)
        basePipelineClassList.add(NavBaseCDPipeline.class)
    }
}
