
package com.sony.sie.cicd.helpers.utilities

    private def getFileUtilsObject(Map conf){
        String keyword = conf.keyword?conf.keyword:""
        String fileType = conf.filetype?conf.filetype:""
        if(keyword=="")
            fileType = "text"
        else if(fileType=="" && conf.filepath.contains(".")){
            def arrText = conf.filepath.split("\\.")
            fileType = arrText[arrText.size()-1]
        }
        fileType = fileType.toLowerCase()
        def fileUtils = null
        switch (fileType){
            case "yaml":
            case "yml":
                echo "read data from yaml file"
                fileUtils = new YamlFileUtils()
                break
            case "properties":
                echo "read data from properties file"
                fileUtils = new PropertiesFileUtils()
                break
            case "xml":
                echo "read data from xml file"
                fileUtils = new XmlFileUtils()
                break
            case "json":
                echo "read data from json file"
                fileUtils = new JsonFileUtils()
                break
            case "github":
                echo "get latest release version"
                fileUtils = new GithubFileUtils()
                break
            default:
                echo "read data from text file"
                fileUtils = new TextFileUtils()
        }
        return fileUtils
    }

    String getValue(Map conf){
        String keyword = conf.keyword?conf.keyword:""
        def fileUtils = getFileUtilsObject(conf)
        def value = fileUtils.getValue(conf.filepath, keyword)
        if(keyword!="") echo "${keyword}: ${value}"
        return value
    }

    void setValue(Map conf, String newVersion){
        String keyword = conf.keyword?conf.keyword:""
        def fileUtils = getFileUtilsObject(conf)
        fileUtils.setValue(conf.filepath, keyword, newVersion)
    }

return this
