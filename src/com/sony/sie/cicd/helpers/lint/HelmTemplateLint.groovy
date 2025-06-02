package com.sony.sie.cicd.helpers.lint
import org.codehaus.groovy.GroovyException

import com.sony.sie.cicd.helpers.utilities.KmjEnv
import com.sony.sie.cicd.helpers.charts.Schema

//Important assumption: current folder is a chart folder for helm unified 
def runHelmTemplateLint(){
    def repoInfo=getHelmUnifiedInfo()
    updateHelmRepo()
    sh """
        skuba helm dep up
    """
    if(repoInfo.configFiles){
        repoInfo.configFiles.each{
            sh("skuba helm template . -f ${it} --debug")
        }
    }else{
        sh("skuba helm template . -f values.yaml --debug")
    }
}

def runHelmTemplateLintSchema(){
    def helmUnifiedInfo=getHelmUnifiedInfo()
    updateHelmRepo()
    sh """
        skuba helm dep up
    """
    def engineChartVersion=getDepEngineChartVersion()
    if(engineChartVersion=="0.0.0"){
        //not engine-microservice
        if(helmUnifiedInfo.configFiles){
            helmUnifiedInfo.configFiles.each{
                sh("skuba helm template . -f ${it} --debug")
            }
        }else{
            sh("skuba helm template . -f values.yaml --debug")
        }
    }else{
        //back up values.yaml
        sh "cp values.yaml values.bak" 
        replaceValues(helmUnifiedInfo)
        if(helmUnifiedInfo.configFiles){    
            sh """
                skuba helm pull ${KmjEnv.ENGINE_CHARTS_REPO_NAME}/${KmjEnv.ENGINE_CHARTS_CHART_NAME} --version=${engineChartVersion} --untar
            """
            String schemaPath="${KmjEnv.ENGINE_CHARTS_CHART_NAME}/schema"
            def valueMap = helmUnifiedInfo.valuesYaml
            def svcNameLst=helmUnifiedInfo.svcNameLst
            def schema=new Schema()
            if(helmUnifiedInfo.depHasCondition){
                echo "Generate schema for every configuration file because dependency has condition."
            }else{
                //create values.schema.json
                schemaJson=schema.getFlattenJsonSchemaMap(schemaPath,engineChartVersion,svcNameLst,valueMap)  
                writeJSON file:"values.schema.json",json:schemaJson,pretty:1 
            }

            //validate
            boolean failed=false 
            def txtResults= StringBuilder.newInstance()
            def txt
            def cfgFiles=helmUnifiedInfo.configFiles
            int n=cfgFiles.size()
            for(int i=0;i<n;i++){
                def fname=cfgFiles[i]
                echo "configuration file: ${fname}"
            
                if(helmUnifiedInfo.depHasCondition){
                    //create values.schema.json                        
                    def configMap=readYaml file: fname
                    def dependencies=helmUnifiedInfo.chartYaml.dependencies?helmUnifiedInfo.chartYaml.dependencies:helmUnifiedInfo.requirementsYaml.dependencies
                    schemaJson=schema.getFlattenJsonSchemaMap2(schemaPath,engineChartVersion,svcNameLst,valueMap,configMap,dependencies)  
                    if(fileExists("values.schema.json"))
                        sh "rm values.schema.json"
                    writeJSON file:"values.schema.json",json:schemaJson,pretty:1 
                }
                //helm lint
                String helmCmd='skuba helm lint . -f '+fname
                status=sh(returnStatus: true, script: helmCmd)  
                if(status!=0){
                    echo "Run command again to get error message."
                    lintResults=runScript(helmCmd)
                    txt= "_${helmCmd}_\n```${lintResults}```\n"
                    txtResults << txt
                    failed=true
                    break
                }else{
                    //helm template
                    helmCmd="skuba helm template . -f ${fname} --debug"
                    status=sh(returnStatus: true, script: helmCmd)  
                    if(status!=0){
                        lintResults=runScript(helmCmd)
                        txt = "_${helmCmd}_\n```${lintResults}```\n"
                        txtResults << txt
                        failed=true
                        break
                    }
                }
            }

            if(failed){
                def fileName="values.schema.json"
                if(fileExists(fileName)){
                    echo "Please download `values.schema.json` into helm chart folder to reproduce errors."
                    archiveArtifacts artifacts: fileName, onlyIfSuccessful: false
                }
                error txtResults.toString()
            }
            if(fileExists("values.schema.json")){
                sh "rm values.schema.json"
            }
            if(fileExists("${KmjEnv.ENGINE_CHARTS_CHART_NAME}")){
                sh "rm -rf ${KmjEnv.ENGINE_CHARTS_CHART_NAME}"
            }
            
        }else{
            sh("skuba helm template . -f values.yaml --debug")
        }
        //restore values.yaml
        sh """
            cp values.bak values.yaml
            rm values.bak
        """
    }
}

def getHelmUnifiedInfo() {
    def info = [:]
    def svcNameLst = []
    def depHasCondition = false;

    //load Chart.yaml and requirements.yaml
    if (fileExists("Chart.yaml")) {
        info.chartYaml = readYaml file: "Chart.yaml"
        if (!info.chartYaml?.dependencies && fileExists("requirements.yaml")) {
            info.requirementsYaml = readYaml file: "requirements.yaml"
        }
        def dependencies = info.chartYaml?.dependencies ?: info.requirementsYaml?.dependencies
        if (dependencies) {
            dependencies.each {
                if (it.name == KmjEnv.ENGINE_CHARTS_CHART_NAME) {
                    svcNameLst << (it.alias ?: it.name)
                    if (it.condition) {
                        depHasCondition = true;
                    }
                }
            }
        }
    }

    info.svcNameLst = svcNameLst
    info.depHasCondition = depHasCondition

    if (fileExists("values.yaml")) {
        info.valuesYaml = readYaml file: "values.yaml"
    }

    info.configFiles = []
    info.configYamls = [:]
    def files = findFiles(glob: 'values-*-*.yaml')
    files.each {
        info.configFiles << it.name
        info.configYamls.put(it.name, readYaml(file: it.name))
    }
    return info
}

void replaceValues(def info){
    def valuesReplaced=false;
    if(info.chartYaml){
        info.chartVersion=info.chartYaml.version
        if(!isSemVer(info.chartVersion)){
            info.chartYaml.version="1.0.0"
            valuesReplaced=true;
        }
        info.chartAppVersion=info.chartYaml.appVersion
        if(!isSemVer(info.chartAppVersion)){
            info.chartYaml.appVersion="1.0.0"
            valuesReplaced=true;
        }
        if(valuesReplaced){
            if(fileExists("Chart.yaml")){
                sh "rm Chart.yaml"
            }
            writeYaml file: "Chart.yaml", data: info.chartYaml
        }
    }   
    info.valuesReplaced=valuesReplaced
}

void restoreValues(def info){
    info.chartYaml.version=info.chartVersion
    info.chartYaml.appVersion=info.chartAppVersion
}

def updateHelmRepo(){
    boolean addRepo=false;
    (status,result)=runShellScript("skuba helm repo list -o json")
    if(status>0){
        addRepo=true;
    }else{
        repoLst=readJSON text:result
        addRepo=!repoLst.any{it.name==KmjEnv.ENGINE_CHARTS_REPO_NAME};
    }

    if(addRepo){
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: KmjEnv.ENGINE_CHARTS_CREDENTIAL_ID, usernameVariable: 'username', passwordVariable: 'password']]) {
            def script="skuba helm repo add engine-helm-virtual ${KmjEnv.ENGINE_CHARTS_REPO_URL} --username ${username} --password ${password}"
            status = sh(returnStatus: true, script: script)    
        }
    }
    status=sh(returnStatus: true, script:"skuba helm repo update ${KmjEnv.ENGINE_CHARTS_REPO_NAME}")
    return status==0
}

private def runShellScript(script) {    
    Date tm = new Date(); 
    def stdoutFile = "${tm.getTime()}.out"    
    script = script + " > " + stdoutFile

    def status = sh(returnStatus: true, script: script)    
    def stdout=status>0?"":sh(returnStdout: true, script: "cat " + stdoutFile)
    sh(returnStatus: true, script: "rm -f " + stdoutFile)
    return [status,stdout.trim()]
}

private def getDepEngineChartVersion(){
    def files=findFiles(glob: "charts/${KmjEnv.ENGINE_CHARTS_CHART_NAME}-*.tgz")
    def engineChartVersion="0.0.0"
    files.each{
        //get the version in right format 'major.minor.patch'
        def str=it.name.minus("${KmjEnv.ENGINE_CHARTS_CHART_NAME}-").minus(".tgz")
        def numbers=str.tokenize('-')[0].tokenize('.')
        for(i=0;i<numbers.size();i++){
            def version=numbers[i]
            if(!version.isNumber()) numbers[i]="0" 
        }
        def ver= numbers.join(".")
        if(compareVer(ver, engineChartVersion) >0)
            engineChartVersion=ver
    }
    return engineChartVersion
}

def runScript(command) {
    script {
        sh script: "set +x ; $command 2>&1 && echo \"status:\$?\" || echo \"status:\$?\" ; exit 0", returnStdout: true
    }
}

@NonCPS
static def isSemVer(String version){
    boolean valid=false
    if(version){
        def parts=version.tokenize('-')
        if(parts.size()<3){
            parts=parts[0].tokenize('.')
            if(parts.size()==3){
                valid=parts[0].isInteger() && parts[1].isInteger() && parts[2].isBigInteger()   
            }
        }
    }
    return valid
}
@NonCPS
static def compareVer(String a,String b){
    List verA = a.tokenize('.')
    List verB = b.tokenize('.')
        
    def commonIndices = Math.min(verA.size(), verB.size())
    
    for (int i = 0; i < commonIndices; ++i) {
        def numA = verA[i].toInteger()
        def numB = verB[i].toInteger()
        
        if (numA != numB) {
        return numA <=> numB
        }
    }      
    // If we got this far then all the common indices are identical, so whichever version is longer must be more recent
    return verA.size() <=> verB.size()
}
return this