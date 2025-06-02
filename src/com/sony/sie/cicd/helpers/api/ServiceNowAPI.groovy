package com.sony.sie.cicd.helpers.api

import groovy.json.JsonSlurperClassic
import org.apache.commons.io.IOUtils
import org.apache.http.client.methods.HttpPatch
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClientBuilder
import org.codehaus.groovy.GroovyException

/**
 * Call to the Service Now api to determine if the user is trying to deploy within an allowed deployment window
 */

class ServiceNowAPI {
    private final def token

    ServiceNowAPI(String token) {
        this.token = token
    }

    def doGet(String url) {
        InputStream inStream = null
        try {
            def conn = new URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer " + token)
            inStream = conn.inputStream
            def jsonParser = new JsonSlurperClassic()
            def data = jsonParser.parse(new InputStreamReader(inStream))
            return data
        } catch (Exception err) {
            throw err
        } finally {
            IOUtils.closeQuietly(inStream)
        }
    }

    def doPost(String urlPath, String data) {
        OutputStream outStream = null
        InputStream inStream = null
        try {
            def conn = new URL(urlPath).openConnection() as HttpURLConnection
            byte[] postDataBytes = data.getBytes("UTF-8");
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer " + token)
            conn.setDoOutput(true);
            outStream = conn.getOutputStream()
            outStream.write(postDataBytes)
            inStream = conn.inputStream
            def jsonParser = new JsonSlurperClassic()
            def response = jsonParser.parse(new InputStreamReader(inStream))
            response.responseCode = conn.getResponseCode()
            return response
        } catch (Exception err) {
            throw new GroovyException("HttpClientHelper.do${"POST"} failed: " + err.getMessage())
        } finally {
            IOUtils.closeQuietly(outStream)
            IOUtils.closeQuietly(inStream)
        }
    }

    def doPatch(String url, String data) {
        def response = null
        def responsePost = null
        try {
            HttpPatch request = new HttpPatch(url)
            request.addHeader("Authorization", "Bearer " + token)
            request.addHeader("Content-Type", "application/json")
            request.addHeader("accept", "application/json")
            request.setEntity(new StringEntity(data))
            def client = HttpClientBuilder.create().build()
            response = client.execute(request)
            def responseCode = response.getStatusLine().getStatusCode()
            def dataContent = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))
            def codeAndJson = new JsonSlurperClassic().parse(dataContent.getText())
            codeAndJson.responseCode = responseCode
            // def codeAndJson = [responseCode:responseCode, json:json)]
            return codeAndJson
        } catch (Exception err) {
            throw new GroovyException("ServiceNow PATCH failed: " + err.getMessage())
        }
    }
}
