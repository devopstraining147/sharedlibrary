
package com.sony.sie.cicd.ci.utilities

void publish(String publishCmd) {
    // withCredentials([[$class: 'StringBinding', credentialsId: 'KMJ_ECR_AWS_ACCESS_KEY_ID', variable: 'AWS_ACCESS_KEY_ID'],
    //                 [$class: 'StringBinding', credentialsId: 'KMJ_ECR_AWS_SECRET_ACCESS_KEY', variable: 'AWS_SECRET_ACCESS_KEY']]) {
    sh """
        export GIT_BRANCH=${env.GIT_BRANCH}
        export GIT_URL=${env.GIT_URL}
        export GIT_COMMIT=${env.GIT_COMMIT}
        export BUILD_NUMBER=${env.BUILD_NUMBER}
        export BUILD_TAG=${env.BUILD_TAG} 
        export BUILD_URL=${env.BUILD_URL} 
        ${publishCmd}
    """
    //}
}

return this
