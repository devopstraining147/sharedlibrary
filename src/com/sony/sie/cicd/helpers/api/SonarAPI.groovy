package com.sony.sie.cicd.helpers.api

import groovy.json.JsonSlurperClassic
import org.apache.commons.io.IOUtils
import static com.sony.sie.cicd.helpers.utilities.KmjEnv.SONARQUBE_PROD_URL

/**
 * API implementation for sonarqube to make it easy to communicate with sonarqube from DSL
 */
class SonarAPI {
    private final def jsonParser = new JsonSlurperClassic()
    private final def apiURL
    private final def token
 
    SonarAPI(String apiURL, String token) {
        this.apiURL = apiURL
        this.token = token
    }

    def doGet(String url) {
        def conn = new URL(url).openConnection() as HttpURLConnection
        String basicAuth = Base64.getEncoder().encodeToString((token+":").getBytes());
        conn.setRequestProperty ("Authorization", "Basic "+basicAuth);
        if(conn.getResponseCode() == 200){
            String response = conn.inputStream.text
            def jsonData = jsonParser.parseText(response)
            return jsonData
        } else {
            String response = conn.errorStream.text
            throw new Exception(response)
        }
        return ""
    }

    def doPost(String url) {
        try{
            def conn = new URL(url).openConnection() as HttpURLConnection
            conn.setRequestMethod("POST")
            String basicAuth = Base64.getEncoder().encodeToString((token+":").getBytes());
            conn.setRequestProperty ("Authorization", "Basic "+basicAuth);
            conn.connect();
            if(conn.getResponseCode() <= 299){
                return conn.getResponseCode()
            } else {
                String response = conn.errorStream.text
                throw new Exception(response)
            }
            return ""
        } catch (Exception err) {
            throw err
        }
    }

    def getStatus(String taskID) {
        String url="$apiURL/api/ce/task?id=$taskID"
        def data = doGet(url)
        if(data && data.task){
            return data.task.status  
        }
        return ""
    }

    def getDefaultQualityGate() {
        String url="$apiURL/api/qualitygates/list"
        def data = doGet(url)
        if(data && data.qualitygates){
            def result = data.qualitygates.find { it.isDefault == true }
            return result.name
        }
        return ""
    }

    def getQualityGate(String projectKey) {
        String url="$apiURL/api/qualitygates/get_by_project?project=$projectKey"
        def data = doGet(url)
        if(data && data.qualityGate){
            return data.qualityGate.name
        }
        return ""
    }

    def selectQualityGate(String projectKey, String qualityGate) {
        String url="$apiURL/api/qualitygates/select?gateName=$qualityGate&projectKey=$projectKey"
        def statusCode = doPost(url)
        if(statusCode){
            return statusCode
        }
        return ""
    }

    def getQualityProfile(String projectName, String language) {
        String url="$apiURL/api/qualityprofiles/search?project=$projectName&language=$language"
        def data = doGet(url)
        if(data && data.profiles){
            return data.profiles[0].name
        }
        return ""
    }

    def getDefaultQualityProfile(String language) {
        String url="$apiURL/api/qualityprofiles/search?language=$language"
        def data = doGet(url)
        if(data && data.profiles){
            def result = data.profiles.find { it.isDefault == true }
            return result.name
        }
        return ""
    }

    def addQualityProfile(String projectKey, String qualityProfile, String language) {
        String url = "$apiURL/api/qualityprofiles/add_project?project=$projectKey&qualityProfile="+java.net.URLEncoder.encode(qualityProfile, "UTF-8")+"&language=$language"
        def statusCode = doPost(url)
        if(statusCode){
            return statusCode
        }
        return ""
    }

    def getPortfolioKey(String portfolioName) {
        String url="$apiURL/api/views/list"
        def data = doGet(url)
        if(data && data.views){
            def result = data.views.find { it.name == portfolioName }
            if(result && result.key){
                return result.key
            }
        }
        return ""
    }

    def getProjectKey(String projectName) {
        String url="$apiURL/api/projects/search?q=$projectName"
        def data = doGet(url)
        if(data && data.components){
            def result = data.components.find { it.name == projectName }
            return result.key
        }
        return ""
    }

    def checkProjectPortfolio(String projectName, String portfolioKey) {
        String url="$apiURL/api/views/projects?key=$portfolioKey&query=$projectName"
        def data = doGet(url)
        if(data && data.results){
            def result = data.results.find { it.name == projectName }
            if (result) {
                return portfolioKey
            }
        }
        String urlList ="$apiURL/api/views/list"
        def dataList = doGet(urlList)
        if(dataList && dataList.views){
            def views = dataList.views
            for(int i=0; i<views.size; i++) {
                String portfolioKeyView = views[i].key
                String urlView="$apiURL/api/views/projects?key=$portfolioKeyView&query=$projectName"
                def dataView = doGet(urlView)
                if(dataView && dataView.results){
                    def result = dataView.results.find { it.name == projectName }
                    if(result) {
                        return portfolioKeyView
                    }
                }
            }
        }
        return ""
    }

    def selectPortfolio(String projectKey, String portfolioKey) {
        String url="$apiURL/api/views/add_project?key="+portfolioKey+"&project="+projectKey
        def statusCode = doPost(url)
        if(statusCode){
            return statusCode
        }
        return ""
    }

    def removePortfolio(String projectKey, String portfolioKey) {
        String url = "$apiURL/api/views/remove_project?key=$portfolioKey&project=$projectKey"
        def statusCode = doPost(url)
        if(statusCode){
            return statusCode
        }
        return ""
    }

    def createPortfolio(String portfolioName){
        String url = "$apiURL/api/views/create?name=${java.net.URLEncoder.encode(portfolioName, 'UTF-8')}"
        def statusCode = doPost(url)
        if(statusCode){
            return statusCode
        }
        return ""
    }

    def searchUserGroup(String userGroupName) {
        String url="$apiURL/api/user_groups/search?q=$userGroupName"
        def data = doGet(url)
        if(data && data.groups){
            def result = data.groups.find { it.name == userGroupName }
            if(result) {
                return true
            }
        }
        return false
    }

    def createGroup(String sonarUserGroupName) {
        String url = "$apiURL/api/user_groups/create?name=$sonarUserGroupName"
        def statusCode = doPost(url)
        if(statusCode){
            return statusCode
        }
        return ""
    }

    def addGroupPermission(String projectKey, String groupName, String permission){
        String url="$apiURL/api/permissions/add_group?groupName=$groupName&permission=$permission&projectKey=$projectKey"
        def statusCode = doPost(url)
        if(statusCode){
            return statusCode
        }
        return ""
    }

    def refreshPortfolio(String portfolioKey){
        String url="$apiURL/api/views/refresh?key=$portfolioKey"
        def statusCode = doPost(url)
        if(statusCode){
            return statusCode
        }
        return ""
    }

    def getBinding(String projectKey){
        String url="$apiURL/api/alm_settings/get_binding?project=$projectKey"
        def data = doGet(url)
        if(data && data.key){
            return data.key
        }
        return ""
    }

    def setBinding(String almSetting = "engine-sonarqube-preprod", boolean monorepo, String projectKey, String repository){
        String url="$apiURL/api/alm_settings/set_github_binding?almSetting=$almSetting&monorepo=$monorepo&project=$projectKey&repository=$repository"
        def statusCode = doPost(url)
        if(statusCode){
            return statusCode
        }
        return ""
    }

    def getProjectBadgeToken(String project) {
        String url="$apiURL/api/project_badges/token?project=$project"
        def data = doGet(url)
        if(data) {
            return data.token
        }
        return ""
    }

    def getProjectBranches(String project){
        String url="$apiURL/api/project_branches/list?project=$project"
        def data = doGet(url)
        if(data && data.branches) {
            def result = data.branches.findAll { it.excludedFromPurge == true }
            if (result) {
                return result.name
            }
        }
        return ""
    }
}
