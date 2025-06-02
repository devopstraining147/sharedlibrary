package com.sony.sie.cicd.helpers.utilities

class KmjEnv {
    static final String GITHUB_API_URL = 'https://github.sie.sony.com/api/v3'
    static final String ECR_HOST_URL = "890655436785.dkr.ecr.us-west-2.amazonaws.com"
    static final String DASH_JENKINS_URL = "https://onejenkins.dash.playstation.net/job/KMJ-CICD/job"
    static final String SERVICENOWCR_URL = "https://playstationstage.service-now.com/api"
    static final String SONAR_VERSION = '3.9.1.2184'
    static final String CHECKMARX_URL = "https://sie.checkmarx.net/cxrestapi"
    static final String SONARQUBE_PROD_URL = "https://engine-sonarqube.prodadmin.navcloud.sonynei.net"
    static final String SONAR_CREDENTIALS_ID = "engine-sonarqube-token"
    static final String ART_URL = "https://artifactory.sie.sony.com/artifactory/"
    static final String ENGINE_CHARTS_REPO_NAME = "engine-helm-virtual"
    static final String ENGINE_CHARTS_REPO_URL = ART_URL + ENGINE_CHARTS_REPO_NAME
    static final String ENGINE_CHARTS_PROD_VIRTUAL_REPO_NAME = "engine-charts-prod-virtual"
    static final String ENGINE_CHARTS_PROD_VIRTUAL_REPO_URL = ART_URL + ENGINE_CHARTS_PROD_VIRTUAL_REPO_NAME
    static final String ENGINE_CHARTS_CREDENTIAL_ID = "engine-artifactory-access"
    static final String ENGINE_CHARTS_CHART_NAME = "engine-microservice"
    static final String ENGINE_CHARTS_FIRST_SCHEMA_VERSION = "2.5.0"
    static final String PRODSEC_NOTIFICATION_URL = "https://prodsec-watchers-api.gt.sonynei.net/v1.0/scan-failure-notification"
}
