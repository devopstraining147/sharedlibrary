package com.sony.sie.cicd.cd.pipelines

import com.sony.sie.cicd.cd.deployProcessors.LacoK8sDeployProcessor
import com.sony.sie.cicd.cd.utilities.LacoServiceNow
import com.sony.sie.cicd.helpers.annotations.CDScope
import com.sony.sie.cicd.helpers.annotations.StageOrder
import com.sony.sie.cicd.helpers.annotations.StageLabel

abstract class LacoBaseCDPipeline extends BaseCDPipeline {

    LacoBaseCDPipeline(def deployConfiguration) {
        super(deployConfiguration)
    }

    void deploymentSetup() {
        deployProcessor = new LacoK8sDeployProcessor()
        serviceNowUtils = new LacoServiceNow(deployConfiguration)
        basePipelineClassList.add(LacoBaseCDPipeline.class)
    }
}
