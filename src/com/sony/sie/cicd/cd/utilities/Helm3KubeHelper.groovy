
package com.sony.sie.cicd.cd.utilities

import org.yaml.snakeyaml.Yaml
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import com.sony.sie.cicd.helpers.utilities.JenkinsSteps
import org.codehaus.groovy.GroovyException

class Helm3KubeHelper extends JenkinsSteps {
    final def helmUtils = new HelmUtils()
    String clusterId
    String namespace

    public Helm3KubeHelper(String clusterId, String namespace){
        this.clusterId = clusterId
        this.namespace = namespace
    }

    def runCommand(String command){
        runHelmCommand(command:command)
    }

    String runScript(String command, Boolean returnOnFail = false) {
        return runHelmCommand(command:command, returnStdout:true, returnOnFail:returnOnFail)
    }

    def getCommandStatus(String command){
        return runHelmCommand(command:command, returnStatus:true)
    }

    def runHelmCommand(def conf){
        conf = [clusterId:clusterId, returnStdout: false, returnStatus: false, returnOnFail: false] << conf
        return helmUtils.runHelmCommand(conf)
    }

    String checkRolloutStatusStatefulSet(String name) {
        try {
            return runScript("skuba kubectl rollout status statefulset/${name} -n ${namespace} --timeout=1h", true)
        } catch (Exception err) {
            return ""
        }
    }

    void doRolloutStatus(String deploymentName) {
        runCommand("skuba kubectl rollout status deploy/${deploymentName} -n ${namespace}")
    }

    String getArgoRolloutNames(String releaseName, Boolean returnOnFail = false) {
        try{
            return runScript("skuba kubectl argo rollouts list rollouts -n ${namespace} | grep ${releaseName}", returnOnFail)
        } catch (Exception err) { 
            return ""
        }
    }

    boolean isUsingHPA(String selectedRolloutName, Boolean returnOnFail = true) {
        try{
            def rtn = runScript("skuba kubectl get hpa -n ${namespace} | grep ${selectedRolloutName}", returnOnFail)
            return (rtn && rtn != "")
        } catch (Exception err) { 
            return false
        }
    }

    boolean isUsingKEDA(String selectedRolloutName, Boolean returnOnFail = true) {
        try{
            def rtn = runScript("skuba kubectl get scaledobject ${selectedRolloutName} -n ${namespace} | grep ${selectedRolloutName}", returnOnFail)
            return (rtn && rtn != "")
        } catch (Exception err) { 
            return false
        }
    }

    int getArgoRolloutStep(String rolloutName, Boolean returnOnFail = false) {
        String rolloutStepList = runScript("skuba kubectl argo rollouts list rollouts -n ${namespace}", returnOnFail)
        def lineList = rolloutStepList.split("\n")
        for(int i=0; i < lineList.size(); i++){
            def lineInfo = lineList[i].split()
            if(lineInfo[0] == rolloutName) {
                int step = lineInfo[3].split("/")[0].toInteger()
                echo "Current Rollout Step: ${step}"
                return step
            }
        }
    }

    def checkArgoRolloutStatus(String rolloutName, Boolean returnOnFail = false) {
        def rtn = runScript("skuba kubectl argo rollouts status ${rolloutName} -n ${namespace} -w=false", returnOnFail)
        if(rtn != null && rtn != "") {
            //get the last line
            rtn = rtn.split("\n")[-1].toLowerCase()
            echo "argo rollouts status: ${rtn}"
            switch (rtn) {
                case ~/.*healthy.*/:
                    return "healthy"
                    break
                case ~/.*progressing.*/:
                    return "progressing"
                    break
                case ~/.*paused.*/:
                    return "paused"
                    break
                case ~/.*degraded.*/:
                    return "degraded"
                    break
                default:
                    return rtn
            }
        } else {
            throw new GroovyException("Can not get argo rollouts status")
        }
    }
    
    def checkAnalysisrunStatus(def argoRolloutNames, Boolean returnOnFail = true) {
        String returnStatus = ""
        def arrRolloutNames = argoRolloutNames.split("\n")
        for(int n=0; n < arrRolloutNames.size(); n++){
            String rolloutName = arrRolloutNames[n].split()[0]
            def rtn = runScript("skuba kubectl get analysisrun -n ${namespace} | grep ${rolloutName}", returnOnFail)
            if(rtn != null && rtn != "") {
                def arr = rtn.split("\n")
                if(arr.size()>0) {
                    def arrLine = arr[-1].split()
                    echo "${arrLine}"
                    //Terminate an AnalysisRun
                    runScript("skuba kubectl argo rollouts terminate analysisrun ${arrLine[0]} -n ${namespace}", returnOnFail)
                    if(arrLine[1] != "Successful") returnStatus = arrLine[1]
                }
            }
        }
        if(returnStatus != "") throw new GroovyException("Argo Analysis ${returnStatus}")
    }

    def promoteArgoRollout(String rolloutName, String options="") {
        if(checkArgoRolloutStatus(rolloutName, true) == "paused") {
            runCommand("skuba kubectl argo rollouts promote ${rolloutName} ${options} -n ${namespace}")
        }
    }

    def abortArgoRollout(String rolloutName) {
        if(checkArgoRolloutStatus(rolloutName, true) == "paused") {
            runCommand("skuba kubectl argo rollouts abort ${rolloutName} -n ${namespace}")
        }
    }

    void checkDeployStatus(String releaseName) {
        if(releaseName!=null && releaseName!=''){
            echo "Check the release status whether it is DEPLOYED or not"
            if(ifReleaseNameExist(releaseName, "--deployed")==false){
                String msg = "${releaseName} is not DEPLOYED successfully! Please check the console log of helm install"
                echo "${msg}", 31
                throw new GroovyException(msg)
            }
            String temp = getDeployments(releaseName)
            if(temp!=""){
                def arr = temp.split("\n")
                if (arr){
                    for(int i=0; i < arr.size(); i++){
                        temp = arr[i].trim()
                        int n = temp.indexOf(" ")
                        if(n>0){
                            doRolloutStatus(temp.substring(0, n))
                        }
                    }
                }
            }
        }
    }

    String helmDiffUpgrade(String releaseName, String opt=''){
        // i.e. opt = "-f ./values-e1-np.yaml"
        return runHelmCommand([command:"skuba helm diff upgrade --allow-unreleased --no-color ${releaseName} . ${opt} --namespace ${namespace}", returnStdout: true])
    }

    void installDeployment(String releaseName, String opt='') {
        echo "Installing helm deployment"
        runCommand("skuba helm install ${releaseName} . ${opt} --namespace ${namespace}")
    }

    void upgradeDeployment(String releaseName, String opt='') {
        echo "Upgrading helm deployment"
        runCommand("skuba helm upgrade ${releaseName} . ${opt} --install --namespace ${namespace}")
    }

    void helmTemplate(String releaseName, String opt) {
        echo "helm template"
        runCommand("skuba helm template . ${opt} --name-template ${releaseName} --namespace ${namespace} --debug")
    }

    void helmTemplateOutput(String releaseName, String opt) {
        echo "helm template output"
        runCommand("skuba helm template . ${opt} --name-template ${releaseName} --namespace ${namespace} > output.yaml")
    }

    void deleteDeployment(String releaseName, String opt="") {
        if (ifReleaseNameExist(releaseName)) {
            echo "Deleting ${releaseName} deployment"
            runCommand("skuba helm uninstall $opt ${releaseName} --namespace ${namespace}")
        }
    }

    boolean rollback(String rollbackRevision, String releaseName) {
        if(rollbackRevision !='') {
            echo "Rollback deployment"
            String currentRevision = getReleaseRevision(releaseName)
            if (currentRevision != rollbackRevision) {
                runCommand("skuba helm rollback ${releaseName} ${rollbackRevision} --namespace ${namespace}")
                return true
            }
        }
        return false
    }

    String getReleaseRevision(String releaseName) {
        String revision = ''
        String historyList = runScript("skuba helm history ${releaseName} --max 1 --namespace ${namespace}", true)
        if(historyList!=""){
            def arrHistory = historyList.split("\n")
            if (arrHistory){
                int len=arrHistory.size()-1
                String temp = arrHistory[len]
                def arrRelease = temp.split()
                if (arrRelease){
                    revision = arrRelease[0].trim()
                    echo "Release revision: ${revision}"
                }
            }
        } else {
            def logContent = new JenkinsUtils().getConsoleLogContent("Error:")
            if (logContent.contains("Error:") && !logContent.contains("release: not found")) {
                currentBuild.result = 'ABORTED'
                throw new GroovyException("Failed to Get Release Revision")
            }
        }
        return revision
    }

    String getChartVersion(String releaseName) {
        String strVersion = ''
        try{
            String historyList = runScript("skuba helm history ${releaseName} --max 1 --namespace ${namespace}", true)
            if(historyList!=""){
                def arrHistory = historyList.split("\n")
                if (arrHistory){
                    int len=arrHistory.size()-1
                    strVersion = helmUtils.formatChartVersion(arrHistory[len])
                    echo "Chart version of ${releaseName}: ${strVersion}"
                }
            }
        } catch (Exception err) { 
            return ""
        }
        return strVersion
    }

    String getReleaseName(String opt = "--deployed", String nameFilter = "${env.RELEASE_NAME}") {
        String foundRelease = ''
        int maxReleasesToFetch = 5
        String listReleases = runScript("skuba helm list --filter \"${nameFilter}\" ${opt} --short --date --max ${maxReleasesToFetch} --reverse --namespace ${namespace}")
        if(listReleases!=""){
            def arrReleases = listReleases.split("\n")
            if (arrReleases){
                boolean isTestRepo = new JenkinsUtils().isTestRepo()
                for(int i=0; i < arrReleases.size(); i++){
                    String release = arrReleases[i]
                    if(opt == "--deployed" && !isTestRepo && !nameFilter.contains("canary-")){
                        //exclude the canary deployments
                        if(!release.contains("canary-")) {
                            foundRelease = release
                        }
                    } else { return release }
                }
            }
        }
        return foundRelease
    }

    int getReleaseCount(String opt = "--deployed", String nameFilter = "${env.RELEASE_NAME}") {
        int releaseCount = 0
        int maxReleasesToFetch = 20
        String listReleases = runScript("skuba helm list --filter \"${nameFilter}\" ${opt} --short --date --max ${maxReleasesToFetch} --reverse --namespace ${namespace}")
        if(listReleases!=""){
            def arrReleases = listReleases.split("\n")
            if (arrReleases){
                releaseCount += arrReleases.size()
            }
        }
        return releaseCount
    }

    def getAllReleases(String opt = "--deployed", String nameFilter = "${env.RELEASE_NAME}") {
        int maxReleasesToFetch = 20
        String listReleases = runScript("skuba helm list --filter \"${nameFilter}\" ${opt} --short --date --max ${maxReleasesToFetch} --reverse --namespace ${namespace}")
        if(listReleases!=""){
            echo "${listReleases}"
            return listReleases.split("\n")
        } else {
            return null
        }
    }
    
        
    boolean ifReleaseNameExist(String releaseName, String opt = "--all") {
        int maxReleasesToFetch = 5
        String lastResult = ""
        boolean moreAvailable = true
        while(moreAvailable) {
            String offset = lastResult == "" ? "" : "--offset ${lastResult}"
            String command = "skuba helm list --filter ${releaseName} ${opt} --short --date --max ${maxReleasesToFetch} --reverse --namespace ${namespace} ${offset}"
            String allReleases = runScript(command)
            if (allReleases != "") {
                def releases = allReleases.split("\n")
                if (releases) {
                    if (releases.size() < maxReleasesToFetch) moreAvailable = false
                    for (int i = 0; i < releases.size(); i++) {
                        String result = releases[i].trim()
                        if (result == releaseName) return true
                        if (i == releases.size() - 1) {
                            lastResult = result
                        }
                    }
                } else moreAvailable = false
            } else moreAvailable = false
        }
        return false
    }

    /*
    * A returnCode of 1 occurs if no change is made during a patch https://github.com/kubernetes/kubernetes/issues/58212
    * That's why this if/else statement is required.
    * This is fixed in kubectl 1.12+ https://github.com/kubernetes/kubernetes/pull/66725
     */
    void updateServiceSelector(String svcName, String releaseName) {
        def res = runScript("skuba kubectl get svc ${svcName} -o yaml -n ${namespace}")
        def svcYaml = (new Yaml()).load(res)
        String newReleaseName = releaseName ? releaseName : ''
        String oldReleaseName = svcYaml.spec.selector.release ? svcYaml.spec.selector.release : ''
        if(newReleaseName == oldReleaseName){
            echo "No need to update service selector. Update would result in no change."
        } else {
            echo "updating Service Selector for ${svcName}"
            def mapSpec = null
            if (releaseName==null)
                mapSpec = "{\"spec\":{\"selector\":{\"release\":null}}}"
            else
                mapSpec = "{\"spec\":{\"selector\":{\"release\":\"${releaseName}\"}}}"
            runCommand("skuba kubectl get svc ${svcName} -o yaml -n ${namespace} | skuba kubectl patch -f - -p '${mapSpec}' -n ${namespace}")
        }
    }

    def getAllServiceNames() {
        try{
            String strServices = runScript("skuba kubectl get svc -n ${namespace} -o name")
            if(strServices!=""){
                def arrServices = strServices.split()
                echo "Services List: ${arrServices}"
                return arrServices
            } else
                echo "No Service found for ${namespace}"
        } catch (Exception err) { 
            echo "[getAllServiceNames] failed: ${err.getMessage()}", 31
            throw err
        }
        return null
    }

    def ifNamespaceExist(String newNamespace) {
        echo "Checking if namespace ${newNamespace} exists"
        try {
            def allNamespaces = runScript("skuba kubectl get ns ${newNamespace} -o name || exit 0")
            if (allNamespaces != "" ) {
                return true
            }
        }catch (Exception err) {
            echo "No namespace found: "+ err.getMessage()
        }
        return false
    }

    def showAllPods() {
        echo "Show all pods for the namespace of ${namespace}"
        runCommand("skuba kubectl get pods --namespace=${namespace}")
    }

    def getAllPods(String releaseName){
        def cmd = "skuba kubectl get pods -o jsonpath='{..metadata.name}' -n ${namespace} | xargs -n1 | sort -u | grep ${releaseName}"
        def rtn = runScript(cmd)
        return rtn != "" ? rtn.split() : null
    }

    def ifPodExist(String podName) {
        echo "Checking if pod ${podName} exists"
        def podStatus = runScript("skuba kubectl get pod ${podName} --namespace=${namespace}")
        return podStatus.contains(podName)
    }

    def ifPodRunning(String podName) {
        echo "Checking if pod ${podName} ok"
        def podStatus = runScript("skuba kubectl get pod ${podName} --namespace=${namespace}")
        return podStatus.contains("Running")
    }

    def deletePod(String podName) {
        if (ifPodExist(podName)) {
            runCommand "skuba kubectl delete pod ${podName} --namespace=${namespace}"
        }
    }

    String getDeployments(String releaseName) {
        return runScript("skuba kubectl get deployments -n ${namespace} | grep ${releaseName}")
    }
}
