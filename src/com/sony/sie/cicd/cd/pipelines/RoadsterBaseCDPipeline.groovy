package com.sony.sie.cicd.cd.pipelines

import com.sony.sie.cicd.cd.deployProcessors.RoadsterK8sDeployProcessor
import com.sony.sie.cicd.cd.utilities.RoadsterServiceNow
import com.sony.sie.cicd.helpers.annotations.*

abstract class RoadsterBaseCDPipeline extends BaseCDPipeline {

    RoadsterBaseCDPipeline(def deployConfiguration) {
        super(deployConfiguration)
    }

    void deploymentSetup() {
        deployProcessor = new RoadsterK8sDeployProcessor()
        serviceNowUtils = new RoadsterServiceNow(deployConfiguration)
        basePipelineClassList.add(RoadsterBaseCDPipeline.class)
    }
}
