
package com.sony.sie.cicd.helpers.utilities

String getValue(String fileName, String keyword){
    if(fileExists(fileName)) {
        def data = readProperties file: fileName
        echo "Properties data: $data"
        if(data == null || data==[])
            return ''
        if(data[keyword] != null) return data[keyword]
    } else if (fileExists('version.yaml') && keyword == 'version') {
        echo "version migration step... reading from version.yaml, value will be moved to ${fileName}..."
        def data = readYaml file: 'version.yaml'
        echo "version data from yaml for migration: ${data}"
        sh "echo \"${keyword}=${data[keyword]}\" >> ${fileName}"
        if(data == null || data==[])
            return ''
        if(data[keyword] != null) return data[keyword]
    }
    return ''
}

void setValue(String fileName, String keyword, String newValue){
    if(fileExists(fileName)) {
        def data = readProperties file: fileName
        if(data && data!=[]) {
            data[keyword] = newValue
            sh "rm ${fileName}"
            def content = data.collect{entry->entry.key+"="+entry.value}.join('\n')
            writeFile file: fileName, text: content
            sh "cat ${fileName}"
        }
    } else if (fileExists('version.yaml') && keyword == 'version') {
        echo "${fileName} does not exist in this repo, migrating from version.yaml to ${fileName}"
        sh "echo \"${keyword}=${newValue}\" >> ${fileName}"
        removeLegacyVersion()
    }
}

void removeLegacyVersion() {
    if(fileExists('version.yaml')) {
        echo "Cleaning up legacy version.yaml file..."
        sh "git rm version.yaml"
    }
}

return this
