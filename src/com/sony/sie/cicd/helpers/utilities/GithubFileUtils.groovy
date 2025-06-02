
package com.sony.sie.cicd.helpers.utilities

import groovy.json.JsonSlurper
import groovy.json.JsonSlurperClassic
import groovy.json.JsonOutput

String getValue(String filename, String keyword){
     def release= new GitUtils().getLatestRelease(env.ORG_NAME,env.REPO_NAME)
     return release?release.tag_name:"1.0.0"
}

void setValue(String filename, String keyword, String newValue){
}

return this

