
package com.sony.sie.cicd.helpers.utilities

String getValue(String fileName, String keyword){
    String value=""
    if(fileExists(fileName)) {
        String strText = readFile fileName
        if(keyword==""){
            value = strText
        } else {
            def arrText = strText.split("\n")
            for (int i = 0; i < arrText.size(); i++) {
                String strLine = arrText[i]
                if (strLine.contains(keyword)) {
                    int startIndex = strLine.indexOf("=")
                    if (startIndex >= 0) {
                        value = strLine.substring(startIndex + 1).trim()
                    }
                    break
                }
            }
        }
    }
    return value
}

void setValue(String fileName, String keyword, String newValue){
    if(fileExists(fileName)) {
        if(keyword==""){
            writeFile file: fileName, text: newValue
        } else {
            String strText = readFile fileName
            def arrText = strText.split("\n")
            for (int i = 0; i < arrText.size(); i++) {
                String strLine = arrText[i]
                if (strLine.contains(keyword)) {
                    int startIndex = strLine.indexOf("=")
                    if (startIndex >= 0) {
                        String value = strLine.substring(startIndex + 1).trim()
                        arrText[i] = strLine.replace(value, newValue)
                        strText = arrText.join("\n")
                        writeFile file: fileName, text: strText
                    }
                    break
                }
            }
        }
    }
}

return this
