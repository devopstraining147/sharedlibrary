package com.sony.sie.cicd.cd.utilities

import com.sony.sie.cicd.helpers.utilities.JenkinsSteps
import com.sony.sie.cicd.helpers.api.HttpClientApi
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import org.codehaus.groovy.GroovyException
import static com.sony.sie.cicd.helpers.utilities.KmjEnv.ECR_HOST_URL

class K8sDeployTest extends JenkinsSteps {
    final def jenkinsUtils = new JenkinsUtils()
    Map deployDefinition

    public K8sDeployTest(Map deployDefinition){
        this.deployDefinition = deployDefinition
        this.deployDefinition.hasSystemTest = 'NO'
    }

    def exeClosure(Closure body) {
        if(body != null){
            body.resolveStrategy = Closure.DELEGATE_FIRST
            body.delegate = this
            body()
        }
    }

    void setTestClosureType(String testClosureType) {
        deployDefinition.testClosureType = testClosureType
    }

    def findEndpoints(String serviceName, Boolean isDarkside) {
        String cmd = ""
        if(isDarkside){
            cmd = """
                skuba kubectl --namespace=${deployDefinition.namespace} \\
                get endpoints -l release=${deployDefinition.newReleaseName},app=${serviceName} \\
                -o jsonpath='{range .items[*]}{@.metadata.name}{","}{@.subsets[0].addresses[*].ip}{"\\n"}{end}'
                """
        } else {
            cmd = """
                skuba kubectl --namespace=${deployDefinition.namespace} \\
                get endpoints ${serviceName} \\
                -o jsonpath='{.metadata.name}{","}{.subsets[0].addresses[*].ip}{"\\n"}'
                """
        }
        def result = sh(returnStdout: true, script: cmd)

        // example output
        //catalyst-example-v20190810015720-example-api,192.168.52.25 192.168.57.224
        //catalyst-example-v20190810015720-example-grpc,192.168.52.26 192.168.55.158
        echo "${result}"
        def resMap = [:]
        def splitResult = result.split("\n")
        for (int i = 0; i < splitResult.size(); i++) {
            def item = splitResult[i]
            def splitItem = item.split(",")
            if (splitItem[1].contains(" ")) {
                resMap[splitItem[0]] = splitItem[1].split(" ")
            } else {
                resMap[splitItem[0]] = [splitItem[1]]
            }
        }
        echo "found Endpoints: ${resMap}"
        // final structure resMap["releaseName"] = ["ips",...]"
        return resMap
    }

    def convertCheck(def check) {
        def retString = "["
        check.each{
            if(it instanceof String){
                retString += /"${it}",/
            } else {
                reString += /${it},/
            }
        }
        retString += "]"
        return retString
    }

    /**
     * Constructs the input required to run the systemtesttesturls container.
     * @param conf Map of ...
     *   [String serviceName, int port, String healthcheckPath,
     *   list<int>|list<String>|int|String check, boolean assertFound,
     *   int timeout, boolean isDarkside]
     * @return
     */
    def systemTestTestUrls(Map conf) {
        //required values:
        // [[endpoint, check, assertFound],[endpoint, check, assertFound]]
        // ex. '''[["https://reddit.com",429, true],...]'''
        def serviceEndpoints = findEndpoints(conf.serviceName, conf.isDarkside)
        def stringCheck = convertCheck(conf.assertOn)
        def inputString = "["
        serviceEndpoints.each{ serviceEndpoint ->
            serviceEndpoint.value.each { endpoint ->
                inputString += /["${constructUrl("http://" + endpoint, conf.port, conf.healthcheckPath)}", ${stringCheck}, ${conf.assertFound}/
                if(conf.timeout) {
                    inputString += /, ${conf.timeout}/
                }
                inputString += /],/
            }
            inputString += /]/
        }
        deployDefinition.hasSystemTest = 'YES'
        //test pod has to be all lowercase and less than 63 chars
        def testPodName = "kmj-cicd-test-${UUID.randomUUID().toString()}".take(60)
        def command = /groovy systemTestTestUrls.groovy '${inputString}'/
        try {
            String cmd = """
                skuba kubectl --namespace=${deployDefinition.namespace} \\
                run ${testPodName} \\
                --attach=true \\
                --restart=Never \\
                --rm=true \\
                --image=${ECR_HOST_URL}/catalyst/systemtesttesturls:0.0.4 \\
                ${command}
                """
            timeout(10) {
                echo "Running system test"
                sh(script: cmd)
            }
        }
        catch (Exception err) {
            String msg = "Test pod " + testPodName + " failed in systemTestTestUrls: " + err.getMessage()
            echo msg
            throw new GroovyException(msg)
        }
    }

    def curlEndpoint(String endpoint, def assertOn) {
        deployDefinition.hasSystemTest = 'YES'
        String timeStamp = new Date().format("yyyyMMddHHmmss")
        def testPodName = "kmj-cicd-test-${timeStamp}"
        def curlCommand = "curl -sS"
        if(assertOn.size() ==1 && (assertOn[0] instanceof Integer)) curlCommand = "curl -o /dev/null -s -w \"%{http_code}\n\""
        try {
            String cmd = """
                skuba kubectl --namespace=${deployDefinition.namespace} \\
                run ${testPodName} \\
                --attach=true \\
                --restart=Never \\
                --rm=true \\
                --image=${ECR_HOST_URL}/raptor/alpine:3.6-curl \\
                --command -- ${curlCommand} ${endpoint}
                """
            timeout(10) {
                def result = sh(returnStdout: true, script: cmd)
                echo "${result}"
                return result
            }
        }
        catch (Exception err) {
            String msg = "Test pod " + testPodName + " failed in curlEndpoint: " + err.getMessage()
            echo msg
            throw new GroovyException(msg)
        }
    }

    def assertEndpoint(Map conf) {
        conf = [endpoint: '', assertOn: [], assertFound: true] << conf
        if (!conf.assertOn) {
            echo "What to assert on should not be empty"
            throw new GroovyException("What to assert on should not be empty")
        }
        if (conf.endpoint == null || conf.endpoint == '') {
            echo "The endpoint should not be empty"
            throw new GroovyException("The endpoint should not be empty")
        }
        def output = curlEndpoint(conf.endpoint, conf.assertOn)
        if(output && output!="") {
            checkResponseMsg(output)
            conf.assertOn.each { assertion ->
                echo("Checking: ${assertion} in the response")
                if (output.contains(assertion.toString()) != conf.assertFound) {
                    String flag = conf.assertFound ? " NOT" : ""
                    String msg = "${assertion} DOES${flag} exist in the response: ${output}"
                    echo msg
                    throw new GroovyException(msg)
                }
            }
        } else {
            echo "No Response message returned"
            throw new GroovyException("No Response message returned")
        }
    }

    def checkResponseMsg(String msg){
        String output = msg.toLowerCase()
        //echo "Endpoint validation"
        echo """
            Endpoint Response Message:
            ${msg}
            """
        if (output.contains(">forbidden<") || output.contains("\"httpstatus\":403") || output.contains("\"status\": 403")){
            echo "Endpoint access denied!"
            throw new GroovyException("Endpoint access denied: ${msg}")
        }

        if (output.contains("\"httpstatus\":404") || output.contains("\"status\":404")){
            echo "Endpoint url is not correct!"
            throw new GroovyException("Endpoint url is not correct: ${msg}\n")
        }
    }

    def createEndpoint(Map conf) {
        if(conf.endpoint) {
            return conf.endpoint
        } else {
            String port = conf.port ? ":${conf.port}" : ""
            return "${conf.serviceName}${port}/${conf.healthcheckPath}"
        }
    }

    def shortenHostInEndpoint(String endpoint) {
        def pattern = "(^[^:\\/]{0,63})[^:\\/]*(.*)"
        return endpoint.replaceFirst(pattern) { "${it[1]}${it[2]}" }
    }

    def constructUrl(String host, Integer port, String path) {
        String tport = port ? ":${port}" : ""
        return "${host}${tport}/${path}"
    }

    def endpointAssertNotFound(Map params) {
        def conf = [assertFound: false] << params
        conf.endpoint = shortenHostInEndpoint(createEndpoint(params))
        assertEndpoint(conf)
    }

    def endpointAssertNotFoundDS(Map params) {
        def conf = [assertFound: false] << params
        conf.endpoint = shortenHostInEndpoint(deployDefinition.newReleaseName + "-" + createEndpoint(conf))
        assertEndpoint(conf)
    }

    def endpointAssertFound(Map params) {
        def conf = [assertFound: true] << params
        conf.endpoint = shortenHostInEndpoint(createEndpoint(params))
        assertEndpoint(conf)
    }

    def endpointAssertFoundDS(Map params) {
        def conf = [assertFound: true] << params
        conf.endpoint = shortenHostInEndpoint(deployDefinition.newReleaseName + "-" + createEndpoint(conf))
        assertEndpoint(conf)
    }

    /**
     * Checks FOUND for all pods within a deployment on LIGHTSIDE given a certain serviceName, port, path, and other conditions
     * @param conf A map which contains the following: [serviceName:String, port:Int, healthcheckPath:String, assertOn:list<int>|list<String>|int|String, timeout:Int (in ms)]
     * @return
     */
    def endpointAssertAllPodsFound(Map params) {
        def conf = [assertFound:true, isDarkside: false] << params
        systemTestTestUrls(conf)
    }

    /**
     * Checks FOUND for all pods within a deployment on DARKSIDE given a certain serviceName, port, path, and other conditions
     * @param conf A map which contains the following: [serviceName:String, port:Int, healthcheckPath:String, assertOn:list<int>|list<String>|int|String, timeout:Int (in ms)]
     * @return
     */
    def endpointAssertAllPodsFoundDS(Map params) {
        def conf = [assertFound:true, isDarkside: true] << params
        systemTestTestUrls(conf)
    }

    /**
     * Checks NOT FOUND all pods within a deployment on LIGHTSIDE given a certain serviceName, port, path, and other conditions
     * @param conf A map which contains the following: [serviceName:String, port:Int, healthcheckPath:String, assertOn:list<int>|list<String>|int|String, timeout:Int (in ms)]
     * @return
     */
    def endpointAssertAllPodsNotFound(Map params) {
        def conf = [assertFound:false, isDarkside: false] << params
        systemTestTestUrls(conf)
    }

    /**
     * Checks NOT FOUND for all pods within a deployment on DARKSIDE given a certain serviceName, port, path, and other conditions
     * @param conf A map which contains the following: [serviceName:String, port:Int, healthcheckPath:String, assertOn:list<int>|list<String>|int|String, timeout:Int (in ms)]
     * @return
     */
    def endpointAssertAllPodsNotFoundDS(Map params) {
        def conf = [assertFound:false, isDarkside: true] << params
        systemTestTestUrls(conf)
    }


    def endpointStatusCheck(Map conf) {
        deployDefinition.hasSystemTest = 'YES'
        def response = new HttpClientApi().getApiResponseStatus(conf.endpoint)
        echo "Endpoint Api Response Status:\n${response}"
        if(response.responseCode!=200){
            String msg = "${response.responseCode}-${response.responseMessage}: ${conf.endpoint}"
            echo msg
            throw new GroovyException(msg)
        }
    }

    def endpointStatusCheck(String endpointApi) {
        endpointStatusCheck endpoint: endpointApi
    }

    def helmTest(Map conf=[:]) {
        conf = [releaseName: deployDefinition.newReleaseName,
                namespace: deployDefinition.namespace,
                stageLabel: "Helm Test"
        ] << conf
        
        dir("${env.REPO_WORKDIR}/${env.APP_CHART_PATH}") {
            stage(conf.stageLabel) {
                new HelmTest().process(conf)
            }
        }
    }

    def process(def EndpointTestList) {
        //EndpointTestList: list of [methodName: methodName, params: conf]
        if(EndpointTestList && EndpointTestList.size() > 0) {
            for(int i=0; i < EndpointTestList.size(); i++){
                def item = EndpointTestList[i]
                if (item) {
                    echo "Endpoint check item: ${item}"
                    if(item.params)
                        this."${item.methodName}"(item.params)
                    else
                        this."${item.methodName}"()
                } else {
                    exeClosure EndpointTestList
                    break
                }
            }
        } else { //it is closure
           exeClosure EndpointTestList
        }
    }
}
