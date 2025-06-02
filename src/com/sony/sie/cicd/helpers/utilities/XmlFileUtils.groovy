    
package com.sony.sie.cicd.helpers.utilities

import org.json.JSONObject;
import org.json.XML;

String getValue(String fileName, String keyword){
    String value=""
    if(fileExists(fileName)) {
        String endTab = "</"+ keyword + ">"
        String strText = readFile fileName
        if(fileName.contains("pom.xml")) {
            JSONObject xmlJSONObj = XML.toJSONObject(strText)
            value = xmlJSONObj.project["${keyword}"]
        } else {
            def arrText = strText.split("\n")
            for (int i = 0; i < arrText.size(); i++) {
                String strLine = arrText[i]
                if (strLine.contains(endTab)) {
                    value = getXmlData(strLine, keyword)
                    break
                }
            }
        }
    }
    return value
}

//get XML foramt data: ie: <version>1.0.0</version> return 1.0.0
private def getXmlData(String strLine, String keyword){
    //String startStr="<$keyword>"
    String endTab="</$keyword>"
    strLine = strLine.replace(endTab, "")
    int startIndex = strLine.indexOf(">")
    if(startIndex>0)
        return strLine.substring(startIndex+1)
    else
        return ""
}

void setValue(String fileName, String keyword, String newValue){
    if(keyword=='version' && fileName.contains('pom.xml') && !fileName.contains('.pom.xml'))
        setPomVersion(newValue)
    else if(fileExists(fileName)) {
        if(keyword==""){
            writeFile file: fileName, text: newValue
        } else {
            String endTab = "</"+ keyword + ">"
            String strText = readFile fileName
            def arrText = strText.split("\n")
            boolean parentTag = false
            for (int i = 0; i < arrText.size(); i++) {
                String strLine = arrText[i]
                if (strLine.toLowerCase().contains("<parent>")) {
                    parentTag = true //start of the parent tag
                } else if(parentTag) {
                    if (strLine.toLowerCase().contains("</parent>")) {
                        parentTag = false //end of the parent tag
                    }
                } else if (strLine.contains(endTab)) {
                    value = getXmlData(strLine, keyword)
                    arrText[i] = strLine.replace(value, newValue)
                    strText = arrText.join("\n")
                    strText += "\n"
                    writeFile file: fileName, text: strText
                    break
                }
            }
        }
    }
}

def setPomVersion(String newVersion) {
    echo "set version"
    def cmd = "mvn -B versions:set versions:commit -DnewVersion=$newVersion"
    mvnBuild(cmd)
}

String getPomVersion(String strText){
    JSONObject xmlJSONObj = XML.toJSONObject(strText)
    return xmlJSONObj.project.version
}

def mvnBuild(String cmd) {
    container('maven-build'){
        sh "$cmd"
    }
}

return this
