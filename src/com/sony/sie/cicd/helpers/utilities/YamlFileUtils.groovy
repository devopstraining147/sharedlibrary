
package com.sony.sie.cicd.helpers.utilities

import org.yaml.snakeyaml.Yaml

def convertMapToYaml(def map){
    return new Yaml().dump(map)
}

def convertYamlToJson(String yamlString) {
    return new Yaml().load(yamlString);
}

String getValue(String fileName, String keyword){
    if(fileExists(fileName)) {
        def data = readYaml file: fileName
        echo "yaml data: $data"
        if(data == null || data==[])
            return ''
        if(data[keyword] != null) return data[keyword]
    }
    return ''
}

void setValue(String fileName, String keyword, String newValue){
    if(fileExists(fileName)) {
        def data = readYaml file: fileName
        if(data && data!=[]) {
            data[keyword] = newValue
            sh "rm ${fileName}"
            writeYaml file: fileName, data: data
            sh "cat ${fileName}"
        }
    }
}

return this

