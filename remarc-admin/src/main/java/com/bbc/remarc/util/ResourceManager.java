/*
 * Copyright (C) 2016 BBC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bbc.remarc.util;

import com.bbc.remarc.db.MongoClient;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

/**
 * Manager class to handle CRUD operations on Content resources
 *
 * @author Peter Brock
 * @author Mark Fearnley
 */
public class ResourceManager {

	private static Logger log = Logger.getLogger(ResourceManager.class
			.getSimpleName());

	public static boolean deleteResourceForId(String resourceDir, ResourceType resourceType, String id) {

		log.debug("deleting resources for id " + id);

		boolean success = true;

		File contentDir;

		//get folder by resourceType
		switch (resourceType) {
			case IMAGE :
				contentDir = new File(resourceDir + Configuration.IMAGE_DIR_NAME);
				break;
			case AUDIO :
				contentDir = new File(resourceDir + Configuration.AUDIO_DIR_NAME);
				break;
			case VIDEO :
				contentDir = new File(resourceDir + Configuration.VIDEO_DIR_NAME);
				break;
			default :
				throw new RuntimeException("ERROR! Trying to delete content for invalid type " + resourceType);
		}

		//get all files that match the id & delete
		FileFilter fileFilter = new WildcardFileFilter(id + ".*");
		File[] files = contentDir.listFiles(fileFilter);
		for (File f : files) {
			try {
				FileUtils.forceDelete(f);
				log.debug("deleted file " + f.getPath());
			} catch (IOException e) {
				log.error("ERROR! couldn't delete file " + f.getPath());
				success = false;
				break;
			}
		}

		return success;
	}

	public static void processUploadDir(String uploadDir, String resourcesDir) {

		File uploadFolder = new File(uploadDir);

		log.debug("processing resources within upload directory: " + uploadFolder.getPath()
				+ " with resources directory: " + resourcesDir);

		processUploadDir(uploadFolder, resourcesDir);

		//delete everything in the upload directory now that it's been processed
		try {
			FileUtils.cleanDirectory(uploadFolder);
		} catch (IOException e) {
			log.error("ERROR! Could not clean uploadFolder: " + e);
		}
	}

	private static void processUploadDir(File uploadFolder, String resourcesDir) {

		log.debug("processing resources within " + uploadFolder.getPath());

		List<File> directories = new ArrayList<File>();
		HashMap<String, List<File>> fileMap = new HashMap<String, List<File>>();
		HashMap<String, ResourceType> typeMap = new HashMap<String, ResourceType>();
		File properties = null;

		//iterate through all files in the upload folder
		File[] contents = uploadFolder.listFiles();
		for (File f : contents) {

			String filename = f.getName();

			//if the file is a directory, add it to the list, we'll process this later.
			if (f.isDirectory()) {
				log.debug("directory: " + filename);
				directories.add(f);
			} else {

				String extension = FilenameUtils.getExtension(filename);
				String nameId = FilenameUtils.getBaseName(filename);

				log.debug("file: " + nameId + "(" + extension + ")");

				//determine the type of resource for the FILE
				ResourceType resourceType = getTypeFromExtension(extension);

				if (resourceType == null) {

					log.debug("unrecognised extention (" + extension + "), skipping file");

				} else if (resourceType == ResourceType.PROPERTIES) {

					log.debug("found the properties file");
					properties = f;

				} else {

					//add the file to the fileMap, key'd on the nameId
					if (!fileMap.containsKey(nameId)) {
						List<File> fileList = new ArrayList<File>();
						fileList.add(f);
						fileMap.put(nameId, new ArrayList<File>(fileList));
					} else {
						fileMap.get(nameId).add(f);
					}

					//get the current type of resource for the DOCUMENT & update it: VIDEO > AUDIO > IMAGES
					ResourceType documentType = (typeMap.containsKey(nameId)) ? typeMap.get(nameId) : ResourceType.IMAGE;
					documentType = (resourceType.getValue() > documentType.getValue()) ? resourceType : documentType;

					//add the document type to the typeMap, key'd on the nameId
					typeMap.put(nameId, documentType);
				}

			}

		}

		createDocumentsFromFileMap(fileMap, typeMap, properties, resourcesDir);

		log.debug("upload finished for " + uploadFolder.getPath());

		//now call the same method for each directory
		for (File dir : directories) {
			processUploadDir(dir, resourcesDir);
		}

	}

	private static void createDocumentsFromFileMap(
			HashMap<String, List<File>> fileMap,
			HashMap<String, ResourceType> typeMap, File properties,
			String resourcesDir) {

		DB db = MongoClient.getDB();

		Properties documentProps = processPropertiesFile(properties);
		if (documentProps == null) {
			log.error("could not create properties file. Abort directory.");
			return;
		}

		String theme = documentProps.getProperty("theme");
		String decade = documentProps.getProperty("decade");
		if (theme == null && decade == null) {
			log.error("ERROR! Properties file contained neither THEME nor DECADE. Abort directory.");
			return;
		}

		// now we process each key (document) in the hashmap, copying the
		// resources (file array) into the correct folder
		Set<String> keys = fileMap.keySet();
		for (String key : keys) {

			log.debug("processing [" + key + "]");

			// create document with id, theme and decade
			BasicDBObjectBuilder documentBuilder = BasicDBObjectBuilder.start();
			documentBuilder.add("id", key);
			documentBuilder.add("theme", theme);
			documentBuilder.add("decade", decade);

			// based upon the documentType, we can determine all our urls and
			// storage variables
			ResourceType documentType = typeMap.get(key);

			File fileDestDirectory = null;

			// Get the relative base URL from an Environment variable if it has been set
			String relativefileBaseUrl = System.getenv(Configuration.ENV_BASE_URL);
			if (relativefileBaseUrl == null || "".equals(relativefileBaseUrl)) {
				relativefileBaseUrl = Configuration.DEFAULT_RELATIVE_BASE_URL;
			} else {
				relativefileBaseUrl += Configuration.CONTENT_DIR;
			}

			String mongoCollection = "";

			switch (documentType) {
			case IMAGE:
				mongoCollection = "images";
				fileDestDirectory = new File(resourcesDir + Configuration.IMAGE_DIR_NAME);
				relativefileBaseUrl += Configuration.IMAGE_DIR;
				break;
			case AUDIO:
				mongoCollection = "audio";
				fileDestDirectory = new File(resourcesDir + Configuration.AUDIO_DIR_NAME);
				relativefileBaseUrl += Configuration.AUDIO_DIR;
				break;
			case VIDEO:
				mongoCollection = "video";
				fileDestDirectory = new File(resourcesDir + Configuration.VIDEO_DIR_NAME);
				relativefileBaseUrl += Configuration.VIDEO_DIR;
				break;
			default:
				break;
			}

			List<File> files = fileMap.get(key);
			for (File resource : files) {

				log.debug("--- processing [" + resource.getName() + "]");

				String resourceLocation = relativefileBaseUrl + resource.getName();
				String extension = FilenameUtils.getExtension(resource.getName());

				ResourceType fileType = getTypeFromExtension(extension);

				// now determine the value to store the resource under in MongoDB, different if an image or metadata
				String urlKey;
				switch(fileType) {
					case IMAGE: urlKey = "imageUrl"; break;
					case INFORMATION: urlKey = "metadata"; break;
					default: urlKey = (extension + "ContentUrl"); break;
				}

				// If the file is a metadata file, we want to read from it, otherwise we just add the location to the db
                if (fileType == ResourceType.INFORMATION) {
                    String metadata = processMetadata(resource.getPath());
                    documentBuilder.add(urlKey, metadata);
                } else {
                    documentBuilder.add(urlKey, resourceLocation);
                }

			}

			// insert the document into the database
			try {
				DBObject obj = documentBuilder.get();

				log.debug("writing document to collection (" + mongoCollection + "): " + obj);

				db.requestStart();
				DBCollection collection = db.getCollection(mongoCollection);
				collection.insert(documentBuilder.get());

			} finally {
				db.requestDone();
			}

			// write all the resource files to the correct directory
			log.debug("copying resources into " + fileDestDirectory.getPath());

			for (File resource : files) {

                // We don't want to copy the metadata into the directory, so remove it here
                String extension = FilenameUtils.getExtension(resource.getName());
                ResourceType fileType = getTypeFromExtension(extension);

                if (fileType != ResourceType.INFORMATION) {
                    try {
                        FileUtils.copyFileToDirectory(resource, fileDestDirectory);
                    } catch (IOException e) {
                        log.error("ERROR! Couldn't copy resource to directory: " + e);
                    }
                }
			}

		}
	}

	private static String processMetadata(String metadataLoc) {

		String metadata;

		try {
			metadata = IOUtils.toString(new FileInputStream(metadataLoc));
		} catch (IOException e) {
			log.error("ERROR: Unable to parse metadata");
			metadata = "";
		}

		return metadata;
	}

	private static Properties processPropertiesFile(File properties) {

		Properties propertiesObj = null;

		if (properties == null) {

			log.error("ERROR! Upload did not contain properties file. Abort directory.");

		} else {

			try {

				FileInputStream inputStream = new FileInputStream(properties);

				if (inputStream != null) {
					propertiesObj = new Properties();
					propertiesObj.load(inputStream);
					inputStream.close();
				}

			} catch (IOException e) {

				log.error("ERROR! Unable to load properties file " + properties.getName() + " with exception: " + e);
				propertiesObj = null;

			}
		}

		return propertiesObj;
	};

	private static ResourceType getTypeFromExtension(String extension) {

		ResourceType type = null;

		if (extension.equalsIgnoreCase("properties")) {
			type = ResourceType.PROPERTIES;
		} else if (extension.equalsIgnoreCase("jpg") || extension.equalsIgnoreCase("jpeg")) {
			type = ResourceType.IMAGE;
		} else if (extension.equalsIgnoreCase("mp3") || extension.equalsIgnoreCase("ogg")) {
			type = ResourceType.AUDIO;
		} else if (extension.equalsIgnoreCase("mp4") || extension.equalsIgnoreCase("ogv")) {
			type = ResourceType.VIDEO;
		}  else if (extension.equalsIgnoreCase("metadata")) {
			type = ResourceType.INFORMATION;
		}

		return type;
	}

	public static enum ResourceType {
		PROPERTIES(0),
		INFORMATION(1),
		IMAGE(2),
		AUDIO(3),
		VIDEO(4);

		private final int value;

        private ResourceType(final int newValue) {
            value = newValue;
        }

        public int getValue() { return value; }
	}
}
