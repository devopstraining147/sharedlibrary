
package com.sony.sie.cicd.helpers.utilities

import groovy.json.JsonSlurper
import groovy.json.JsonSlurperClassic
import groovy.json.JsonOutput

String getValue(String filename, String keyword){
    if(fileExists(filename)) {
        def data = readJSON file: filename
        if(data."$keyword" != null) {
            return data."$keyword".toString()    
        }
    }
    return ''
}

void setValue(String filename, String keyword, String newValue){
    if(fileExists(filename)) {
        def data = readJSON file: filename
        if(data."$keyword" != null)  {
            data."$keyword" = newValue
            sh "rm ${filename}"
            writeJSON file: filename, json: data
        }
    }
}

return this

