package com.sony.sie.cicd.helpers.api

import groovy.json.JsonSlurperClassic
import org.apache.commons.io.IOUtils
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClientBuilder
import org.codehaus.groovy.GroovyException

/**
 * API implementation for github to make it easy to communicate with github from DSL
 * for test update
 */
class GithubAPI {

    private final def jsonParser = new JsonSlurperClassic()
    private final def apiURL
    private final def token

    GithubAPI(String apiURL, String token) {
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
        }
        return ""
    }

    def doPost(String url, String data, String requestMethod = "POST") {
        OutputStream outStream = null
        try {
            def conn = new URL(url).openConnection() as HttpURLConnection
            byte[] postDataBytes = data.getBytes("UTF-8");
            conn.setRequestMethod(requestMethod);
            conn.setRequestProperty("Authorization", "token $token")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Content-Length", String.valueOf(postDataBytes.length));
            conn.setDoOutput(true);
            outStream = conn.getOutputStream()
            outStream.write(postDataBytes);
            return conn.getResponseCode()
        } catch (Exception err) {
            throw new GroovyException('Post data to Github failed: ' + err.getMessage())
        } finally {
            IOUtils.closeQuietly(outStream)
        }
    }

    def doPostResponseHttpClient(String url, String data) {
        def response = null
        def responsePost = null
        try {
            def post = new HttpPost(url)
            post.addHeader("Authorization", "token $token")
            post.addHeader("Content-Type", "application/json")
            post.setEntity(new StringEntity(data))
            def client = HttpClientBuilder.create().build()
            response = client.execute(post)
            def bufferedReader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))
            responsePost = bufferedReader.getText()
            return responsePost
        } catch (Exception err) {
            throw new GroovyException('Post data to Github failed: ' + err.getMessage())
        }
    }

    def doPut(String url, String requestMethod = "PUT") {
        try {
            def httpCon = new URL(url).openConnection() as HttpURLConnection
            httpCon.setDoOutput(true)
            httpCon.setRequestProperty("Authorization", "token $token")
            httpCon.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            httpCon.setRequestMethod(requestMethod)
            httpCon.connect()
            return httpCon.getResponseCode()
        } catch (Exception err) {
            String msg =  requestMethod + ' an item in Github failed -------' + err.getMessage()
            throw new GroovyException(msg)
        }
    }

    def doPostWithData(String url, String data) {
        try{
            def conn = new URL(url).openConnection() as HttpURLConnection
            conn.setRequestMethod("POST")
            conn.setDoOutput(true)
            String basicAuth = Base64.getEncoder().encodeToString((token+":").getBytes());
            conn.setRequestProperty ("Authorization", "Basic "+basicAuth);
            conn.getOutputStream().write(data.getBytes("UTF-8"));
            conn.connect();
            if(conn.getResponseCode() <= 299){
                String response = conn.inputStream.text
                def jsonData = jsonParser.parseText(response)
                return jsonData
            } else {
                def error = conn.errorStream.text
                throw new Exception(error)
            }
            return ""
        } catch (Exception err) {
            throw new Exception('POST data to Github failed: ' + err.getMessage())
        }
    }

    def doPutWithData(String url, String data) {
        try{
            def conn = new URL(url).openConnection() as HttpURLConnection
            conn.setRequestMethod("PUT")
            conn.setDoOutput(true)
            String basicAuth = Base64.getEncoder().encodeToString((token+":").getBytes());
            conn.setRequestProperty ("Authorization", "Basic "+basicAuth);
            conn.getOutputStream().write(data.getBytes("UTF-8"));
            conn.connect();
            if(conn.getResponseCode() <= 299){
                return conn.getResponseCode()
            } else {
                def error = conn.errorStream.text
                throw new Exception(error)
            }
            return ""
        } catch (Exception err) {
            throw new Exception('PUT data to Github failed: ' + err.getMessage())
        }
    }

    def doDelete(String url) {
        return doPut(url, "DELETE")
    }

    def postStatus(String repoName, String commitSha, String state, String title, String desc, String target_url = '') {
        String url = "$apiURL/repos/$repoName/statuses/$commitSha"
        String data = "{\"state\": \"${state}\", \"description\": \"${desc}\", \"target_url\": \"${target_url}\", \"context\": \"${title}\"}"
        return doPost(url, data)
    }

    def postComment(String repoName, String pr_num, String desc, String keyword = '') {
        String url = findCommentUrl(repoName, pr_num, keyword)
        if (url == "") {
            url = "$apiURL/repos/$repoName/issues/$pr_num/comments"
        }
        String data = "{\"body\": \"${desc}\"}"
        return doPost(url, data)
    }

    def deleteComment(String repoName, String pr_num, String keyword) {
        String url = findCommentUrl(repoName, pr_num, keyword)
        if (url != "") {
            return doDelete(url)
        }
        return 0
    }

    def findCommentUrl(String repoName, String pr_num, String keyword) {
        if (keyword == "") {
            return ""
        }
        keyword = keyword.toLowerCase()
        String url = "$apiURL/repos/$repoName/issues/$pr_num/comments"
        def data = doGet(url)
        if (data) {
            for (int i = 0; i < data.size(); i++) {
                def obj = data[i]
                String str = obj.body.toLowerCase()
                if (str.indexOf(keyword) >= 0) {
                    return obj.url
                }
            }
        }
        return ""
    }

    def getTeamByName(String orgs, String teamName) {
        String url = "$apiURL/orgs/$orgs/teams/$teamName"
        def data = doGet(url)
        if (data) {
            return true
        }
        return false
    }

    def getBranchSHA(String orgs, String repoName, String branchName){
        String url="$apiURL/repos/$orgs/$repoName/branches/$branchName"
        def data = doGet(url)
        if(data && data.commit) {
            return data.commit.sha
        }
        return ""
    }

    def checkBranch(String orgs, String repoName, String branchName){
        String url="$apiURL/repos/$orgs/$repoName/branches/$branchName"
        def data = doGet(url)
        if(data && data.name) {
            return true
        }
        return false
    }

    def getFile(String orgs, String repoName, String path, String branchName) {
        String url="$apiURL/repos/$orgs/$repoName/contents/$path?ref=$branchName"
        def data = doGet(url)
        if(data) {
            return data
        }
        return ""
    }

    def updateFile(String orgName, String repoName, String data, String path) {
        String url="$apiURL/repos/$orgName/$repoName/contents/$path"
        def statusCode = doPutWithData(url, data)
        if(statusCode){
            return statusCode
        }
        return ""
    }

    def createBranch(String orgName, String repoName, String data) {
        String url="$apiURL/repos/$orgName/$repoName/git/refs"
        def res = doPostWithData(url, data)
        if(res) {
            return res
        }
        return ""
    }

    def createPR(String orgName, String repoName, String data) {
        String url="$apiURL/repos/$orgName/$repoName/pulls"
        def res = doPostWithData(url, data)
        if(res) {
            return res.url
        }
        return ""
    }
}
