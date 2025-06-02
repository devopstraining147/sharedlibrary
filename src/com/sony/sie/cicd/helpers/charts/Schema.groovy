package com.sony.sie.cicd.helpers.charts
import org.codehaus.groovy.GroovyException


def getFlattenJsonSchemaMap(String schemaPath,String version,def svcNameLst,def valueMap,def refsMap=[:]){
    assert svcNameLst: "Code Error: Service Chart's dependencies do not include engine chart."
    assert valueMap: "Code Error: valuesMap that loaded from values.yaml is not provided."
    def json=""
    dir(schemaPath){
        def txtSchema=getJsonSchema(schemaPath,svcNameLst,valueMap)
        json=readJSON text:txtSchema
        json=removeRef(json,refsMap)
    }
    def id="values.schema${version}.json"
    json.put("\$id",id)
    return json
}

private def getJsonSchema(String schemaPath,def svcNameLst,def valueMap)
{
    def required=[]
    def properties=[]
    
    valueMap.each{ key,value ->
        if (key == "global") {
            properties.add("\"global\":{\"\$ref\" :\"global.schema.json\"}")
            required.add("\"global\"");
        }else if (svcNameLst.contains(key) && value.appType!=null){
            def refFile="${value.appType}.schema.json"
            if(fileExists(refFile)){
                properties.add("\"${key}\":{\"\$ref\":\"${refFile}\"}")
            }else{
                properties.add("\"${key}\":{\"type\":\"object\"}")
            }
            required.add("\"${key}\"")
        }
    }
    
    String txtData="""
{
    "\$schema\": \"https://json-schema.org/draft-07/schema#\",
    \"\$id\": \"file://${schemaPath}\",
    \"type\": \"object\",
    \"properties\": {
        ${properties.join(",\n\t")}
    },
    \"required\":[${required.join(',')}]
}
    """
    return txtData
}
def getFlattenJsonSchemaMap2(String schemaPath,String version,def svcNameLst,def valueMap,def configMap,def dependencies,def refsMap=[:]){
    echo "getFlattenJsonSchemaMap2"
    def json=""
    dir(schemaPath){
        def txtSchema=getJsonSchema2(schemaPath,svcNameLst,valueMap,configMap,dependencies)
        json=readJSON text:txtSchema
        json=removeRef(json,refsMap)
    }
    def id="values.schema${version}.json"
    json.put("\$id",id)
    echo "${json}"
    return json
}

private def getJsonSchema2(String schemaPath,def svcNameLst,def valueMap,def configMap,def dependencies)
{
    def required=[]
    def properties=[]
    
    valueMap.each{ key,value ->
        if (key == "global") {
            properties.add("\"global\":{\"\$ref\" :\"global.schema.json\"}")
            required.add("\"global\"");
        }else if (svcNameLst.contains(key) && value.appType!=null){
            boolean enabled=true
            int n=dependencies.size()
            for(int i=0;i<n;i++){
                def dep= dependencies[i]
                if(dep.alias ==key || dep.name == key)
                {
                    if(dep.condition){
                        def fields=dep.condition.tokenize('.')
                        def condition=getValueFromMap(configMap,fields)
                        if(condition==null){
                            condition=getValueFromMap(valueMap,fields)
                            enabled=(condition==true)?true:false
                        }else{
                            enabled=condition
                        }
                    }
                }
            }
            if(enabled){
                def refFile="${value.appType}.schema.json"
                if(fileExists(refFile)){
                    properties.add("\"${key}\":{\"\$ref\":\"${refFile}\"}")
                }else{
                    properties.add("\"${key}\":{\"type\":\"object\"}")
                }
                required.add("\"${key}\"")
            }
        }
    }
    
    String txtData="""
{
    "\$schema\": \"https://json-schema.org/draft-07/schema#\",
    \"\$id\": \"file://${schemaPath}\",
    \"type\": \"object\",
    \"properties\": {
        ${properties.join(",\n\t")}
    },
    \"required\":[${required.join(',')}]
}
    """
    return txtData
}

private def getValueFromMap(def map, def keys)
{
    def n=keys.size()
    def value=map
    for(int i=0;i<n;i++){
        if(value instanceof Map){
            value=value.get(keys[i])
        }else{
            value=null
        }
    }
    return value
}

private def removeRef(def json,def refsMap=[:], def depthOfRef=[]){
    def ref="\$ref"
    boolean isRoot=! depthOfRef
    if(json instanceof Map){
        json=removeAnnotation(json,isRoot)
        //include $ref in keys
        if(json.containsKey(ref)){
            def val=json.get(ref)
            if(val instanceof String && val.endsWith(".json")){
                if(refsMap.containsKey(val)){
                    json=refsMap.get(val)
                }else{
                    def refJson=readJSON file: val
                    if(refJson instanceof Map){
                        if(depthOfRef.contains(val)){
                            throw new GroovyException("[Error] There is a loop formed by `\$ref` in schema definition ${val}. Stack:${depthOfRef}")
                        }else{
                            depthOfRef.add(val)
                            json=removeRef(refJson,refsMap,depthOfRef)
                            depthOfRef.pop()
                        }
                    }
                    refsMap.put(val,json)
                }
            }
        }
        //include $ref in values
        def map=[:]
        json.each{ key,value->
            if (value instanceof Map){
                depthOfRef.add(key)
                map.put(key,removeRef(value,refsMap,depthOfRef)) 
                depthOfRef.pop()  
            }else if(value instanceof List){
                depthOfRef.add(key)
                for(def i=value.size()-1;i>-1;i--){
                    if(value[i] instanceof Map){
                        value[i]=removeRef(value[i],refsMap,depthOfRef)                        
                    }
                }
                depthOfRef.pop()
                map.put(key,value)        
            }else{
                map.put(key,value)
            }
        }
        json=map
    }
    return json
}

private def removeAnnotation(def json,boolean isRoot){
    if(json instanceof Map){
        def annotationKeys=["default","description","title"]
        if(!isRoot){
            annotationKeys<<"\$id"<<"\$schema"
        }
        for( key in annotationKeys){
            json.remove(key)
        }
    }
    return json;
}

return this
