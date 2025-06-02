package com.sony.sie.cicd.cd.pipelines

import com.sony.sie.cicd.cd.deployProcessors.KmjK8sDeployProcessor
import com.sony.sie.cicd.cd.utilities.KmjServiceNow
import com.sony.sie.cicd.helpers.annotations.CDScope
import com.sony.sie.cicd.helpers.annotations.StageOrder
import com.sony.sie.cicd.helpers.annotations.StageLabel

abstract class KCBaseCDPipeline extends BaseCDPipeline {

    KCBaseCDPipeline(def deployConfiguration) {
        super(deployConfiguration)
    }

    void deploymentSetup() {
        deployProcessor = new KmjK8sDeployProcessor()
        serviceNowUtils = new KmjServiceNow(deployConfiguration)
        basePipelineClassList.add(KCBaseCDPipeline.class)
    }
}
