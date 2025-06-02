package com.sony.sie.cicd.helpers.api

import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import org.codehaus.groovy.GroovyException

class HttpClientApi {

    HttpClientApi(){}

    def doPut(String urlPath, String data) {
        return doPost(urlPath, data, "PUT")
    }

    def doPost(String urlPath, String data, String requestMethod = "POST") {
        try {
            URL url = new URL(urlPath);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(requestMethod);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            OutputStreamWriter osw = new OutputStreamWriter(connection.getOutputStream());
            osw.write(data);
            osw.flush();
            osw.close();
            return connection.getResponseCode();
        } catch (Exception err) {
            throw new GroovyException("HttpClientHelper.do${requestMethod} failed: " + err.getMessage())
        }
    }

    def doGet(String url, String apiToken = "") {
        try {
            HttpGet get = new HttpGet(url)
            if(apiToken!="") {
                String basicAuth = Base64.getEncoder().encodeToString((apiToken + ":").getBytes())
                get.addHeader("Authorization", "Basic " + basicAuth)
            }
            def client = HttpClientBuilder.create().build()
            def getResponse = client.execute(get)
            def readResponse = new BufferedReader(new InputStreamReader(getResponse.getEntity().getContent()))
            def responseText = readResponse.getText()
            return responseText
        } catch (Exception err) {
            throw new GroovyException('HttpClientHelper.doGet failed: ' + err.getMessage())
        }
    }

    def getApiResponseStatus(String urlPath) {
        try {
            URL url = new URL(urlPath);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection()
            def conf = [responseCode: connection.getResponseCode(), responseMessage: connection.getResponseMessage()]
            return conf
        } catch (Exception err) {
            throw new GroovyException('HttpClientHelper.getApiResponseStatus failed: ' + err.getMessage())
        }
    }
}
