package com.groupstp.init.core;

import com.google.gson.JsonElement;
import com.haulmont.chile.core.model.MetaClass;
import com.haulmont.chile.core.model.Session;
import com.haulmont.cuba.core.app.ConfigStorageAPI;
import com.haulmont.cuba.core.app.importexport.CollectionImportPolicy;
import com.haulmont.cuba.core.app.importexport.EntityImportExportAPI;
import com.haulmont.cuba.core.app.importexport.EntityImportView;
import com.haulmont.cuba.core.entity.Entity;
import com.haulmont.cuba.core.global.Metadata;
import com.haulmont.cuba.core.global.Resources;
import com.haulmont.cuba.core.sys.AppContext;
import com.haulmont.cuba.core.sys.encryption.Sha1EncryptionModule;
import com.haulmont.cuba.security.app.Authenticated;
import org.apache.commons.lang.StringUtils;
import org.eclipse.persistence.internal.jaxb.many.MapEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Created by Антон on 23.09.2018.
 */
@Component
public class InitializationBean {
    private static final Logger log = LoggerFactory.getLogger(InitializationBean.class);

    @Inject
    private EntityImportExportAPI entityImportExport;
    @Inject
    private Sha1EncryptionModule encryptionModule;
    @Inject
    private Resources resources;
    @Inject
    private ConfigStorageAPI configStorage;
    @Inject
    protected Metadata metadata;

    private String version="0.1-SNAPSHOT";
    private String classPath;


    @Authenticated
    public void init() {

        try{
            classPath= AppContext.getProperty("cuba.mainMessagePack");
            classPath=classPath.substring(classPath.lastIndexOf(" ")+1);
            classPath=classPath.replace(".","/")+"/init/";
            Collection<String> filesToImport=findFileOnClassPath(classPath);
            filesToImport.forEach(item->{
                try {
                    importEntitiesFromJson(item);
                }
                catch (Exception e){
                    log.error("error importing from "+item);
                }
            });
        }
        catch (Exception e){
            log.error("error loading file from class path "+classPath+" version is "+version);
        }
    }



    private void importEntitiesFromJson(String filePath) {
        try {
            String json = resources.getResourceAsString(filePath);

            if (StringUtils.isEmpty(json)) {
                log.warn("{} not found", filePath);
                return;
            }
            String fileName = filePath.substring(filePath.lastIndexOf("/") + 1);

            String currentHash = encryptionModule.getPlainHash(json);
            String prevHash = getPreviousHash(fileName);

             if (!Objects.equals(currentHash, prevHash)) {
            log.info("'{}' content has been changed. The import will be performed", filePath);

            com.google.gson.JsonParser p = new com.google.gson.JsonParser();
            entityImportExport.importEntitiesFromJson(json,createEntityImportViewForJSONObject(p.parse(json)));

            setHash(fileName, currentHash);
             }
        } catch (Exception e) {
            log.error(String.format("Failed to import entities on startup from '%s'", filePath), e);
        }
    }

    private String getPreviousHash(String fileName) {
        return configStorage.getDbProperty(fileName);
    }

    private void setHash(String fileName, String hash) {
        configStorage.setDbProperty(fileName, hash);
    }

    private Collection<String> findFileOnClassPath(final String filePath) throws IOException {

        Set<String> result=new HashSet<>();
        final String classpath = System.getProperty("java.class.path");
        final String pathSeparator = System.getProperty("path.separator");
        final StringTokenizer tokenizer = new StringTokenizer(classpath, pathSeparator);

        while (tokenizer.hasMoreTokens()) {
            final String pathElement = tokenizer.nextToken();
            final File directoryOrJar = new File(pathElement);
            final File absoluteDirectoryOrJar = new File(directoryOrJar.getParentFile().getParent()+"\\webapps\\app-core\\WEB-INF\\lib\\app-core-"+version+".jar");
            if (absoluteDirectoryOrJar.isFile()) {
                ZipInputStream zip = new ZipInputStream(new FileInputStream(absoluteDirectoryOrJar));
                while(true) {
                    ZipEntry e = zip.getNextEntry();
                    if (e == null)
                        break;
                    String name = e.getName();
                    if (name.startsWith(filePath)) {

                        if(!name.equalsIgnoreCase(filePath)) result.add(name);
                    }
                }
            }
        }
        return result;

    }

    private EntityImportView createEntityImportViewForJSONObject(JsonElement jsonObject){
        if(jsonObject==null) return null;
        EntityImportView result=null;

        if(jsonObject.isJsonArray()){
            return createEntityImportViewForJSONObject(jsonObject.getAsJsonArray().get(0));
        }
        else{
            if(jsonObject.isJsonObject()){
                try {
                    String className = jsonObject.getAsJsonObject().get("_entityName").getAsString();
                    Session session = metadata.getSession();
                    MetaClass metaClass = session.getClassNN(className);

                    result=new EntityImportView(metaClass.getJavaClass());
                    result.addLocalProperties();

                    try {
                        Map<Field, String> propertyMappingMap = Arrays.asList(metaClass.getJavaClass().getDeclaredFields())
                                .stream()
                                .map(field -> {
                                    return new MapEntry<Field, String>() {

                                        @Override
                                        public Field getKey() {
                                            return field;
                                        }

                                        @Override
                                        public String getValue() {
                                            try {
                                                Object o = field.getDeclaredAnnotations();
                                                return Arrays.asList(field.getDeclaredAnnotations())
                                                        .stream()
                                                        .filter(annotation -> {
                                                            String t = annotation.toString();
                                                            return (annotation.toString().contains("One") || (annotation.toString().contains("Many")));
                                                        })
                                                        .map(Annotation::toString)
                                                        .findFirst().get();
                                            } catch (Exception e) {
                                                return null;
                                            }
                                        }

                                        @Override
                                        public void setKey(Field key) {
                                        }

                                        @Override
                                        public void setValue(String value) {
                                        }
                                    };

                                })
                                .filter(entry -> entry.getValue() != null)
                                .collect(Collectors.toMap(entry -> entry.getKey(), entry -> entry.getValue()));

                        final EntityImportView finalResult = result;
                        propertyMappingMap.entrySet().forEach(entry->{
                            String fieldName=entry.getKey().getName();
                            JsonElement fieldJson=jsonObject.getAsJsonObject().get(entry.getKey().getName());

                            if(entry.getValue().contains("OneToOne")) {
                                finalResult.addOneToOneProperty(fieldName
                                        ,createEntityImportViewForJSONObject(fieldJson));
                            }
                            if(entry.getValue().contains("OneToMany")) {
                                if((fieldJson!=null)&&(fieldJson.isJsonArray())&&(fieldJson.getAsJsonArray().size()>0)){
                                    finalResult.addOneToManyProperty(fieldName
                                            ,createEntityImportViewForJSONObject(fieldJson), CollectionImportPolicy.KEEP_ABSENT_ITEMS);
                                }
                                else {
                                    String fieldClassName=((ParameterizedType)entry.getKey().getGenericType()).getActualTypeArguments()[0].getTypeName() ;
                                    try {
                                        finalResult.addOneToManyProperty(fieldName
                                                ,new EntityImportView((Class<? extends Entity>) Class.forName(fieldClassName)).addLocalProperties()
                                                ,CollectionImportPolicy.KEEP_ABSENT_ITEMS);
                                    } catch (ClassNotFoundException e) {

                                    }
                                }

                            }
                            if(entry.getValue().contains("ManyToOne")) {
                                finalResult.addManyToOneProperty(fieldName
                                        ,createEntityImportViewForJSONObject(fieldJson));
                            }
                            if(entry.getValue().contains("ManyToMany")) {
                                if((fieldJson!=null)&&(fieldJson.isJsonArray())&&(fieldJson.getAsJsonArray().size()>0)){
                                    finalResult.addManyToManyProperty(fieldName
                                            ,createEntityImportViewForJSONObject(fieldJson),CollectionImportPolicy.KEEP_ABSENT_ITEMS);
                                }
                                else {
                                    String fieldClassName=((ParameterizedType)entry.getKey().getGenericType()).getActualTypeArguments()[0].getTypeName() ;
                                    try {
                                        finalResult.addManyToManyProperty(fieldName
                                                ,new EntityImportView((Class<? extends Entity>) Class.forName(fieldClassName)).addLocalProperties()
                                                ,CollectionImportPolicy.KEEP_ABSENT_ITEMS);
                                    } catch (ClassNotFoundException e) {

                                    }
                                }
                            }

                        });
                    }
                    catch (Exception e){
                        return result;
                    }
                }
                catch (Exception e){
                    return null;
                }
            }
        }
        return result;
    }
}
