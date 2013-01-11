/*************************************************************************
 * tranSMART - translational medicine data mart
 * 
 * Copyright 2008-2012 Janssen Research & Development, LLC.
 * 
 * This product includes software developed at Janssen Research & Development, LLC.
 * 
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License 
 * as published by the Free Software  * Foundation, either version 3 of the License, or (at your option) any later version, along with the following terms:
 * 1.	You may convey a work based on this program in accordance with section 5, provided that you retain the above notices.
 * 2.	You may convey verbatim copies of this program code as you receive it, in any medium, provided that you retain the above notices.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS    * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 *
 ******************************************************************/

package fm

import javax.tools.FileObject;
import java.io.File;
import java.net.URL;
import java.net.URLEncoder;
import fm.FmFolder;
import fm.FmFile;
import org.apache.solr.util.SimplePostTool;

import org.codehaus.groovy.grails.commons.ConfigurationHolder;

class FmFolderService {

	boolean transactional = true;
	def config = ConfigurationHolder.config;
	String importDirectory = config.com.recomdata.FmFolderService.importDirectory.toString();
	String filestoreDirectory = config.com.recomdata.FmFolderService.filestoreDirectory.toString();
	String fileTypes = config.com.recomdata.FmFolderService.fileTypes.toString();;
	String solrUrl = config.com.recomdata.solr.baseURL.toString() + "/update";
	
	/**
	 * Imports files processing them into filestore and indexing them with SOLR.
	 *
	 * @return
	 */
	def importFiles() {
		
		if (importDirectory == null || filestoreDirectory == null) {
			if (importDirectory == null) {
				log.error("Unable to check for new files. config.com.recomdata.FmFolderService.importDirectory property has not been defined in the Config.groovy file.");
			}
			if (filestoreDirectory == null) {
				log.error("Unable to check for new files. config.com.recomdata.FmFolderService.filestoreDirectory property has not been defined in the Config.groovy file.");
			}
			return;
		}
		
		if (fileTypes == null) {
			fileTypes = "xml,json,csv,pdf,doc,docx,ppt,pptx,xls,xlsx,odt,odp,ods,ott,otp,ots,rtf,htm,html,txt,log";
		}
		
		processDirectory(new File(importDirectory));
		
	}	
	
	/**
	 * Process files and sub-directories in specified directory.
	 *
	 * @param directory
	 * @return
	 */
	def processDirectory(File directory) {

		for (File file : directory.listFiles()) {
			if (file.isDirectory()) {
				processDirectory(file);
			} else {
				processFile(file);
			}
		}
		
		// TODO: If directory is empty the delete it.

	}

	/**
	 * Processes a file into the filestore associating it with a folder and
	 * indexes file using SOLR
	 *
	 * @param file file to be proceessed
	 * @return
	 */
	def processFile(File file) {
	
		// Use file's parent directory as ID of folder which file will
		// be associated with.
		File directory = file.getParentFile();
		long folderId = Long.parseLong(directory.getName(), 36);
		def fmFolder = FmFolder.get(folderId);
		
		if (fmFolder == null) {
			log.error("Folder with id " + folderId + " does not exist.")
			return;
		}
		//log.info("Folder = " + fmFolder.folderName + " (" + folderId + ")");

		// Check if folder already contains file with same name.
		def fmFile;
		for (f in fmFolder.fmFiles) {
			if (f.originalName == file.getName()) {
				fmFile = f;
				break;
			}
		}
		// If it does, then use existing file record and increment its version.
		// Otherwise, create a new file.
		if (fmFile != null) {
			fmFile.fileVersion++;
			fmFile.fileSize = file.length();
			fmFile.linkUrl = "";
			log.info("File = " + file.getName() + " (" + fmFile.id + ") - Existing");
		} else {
			fmFile = new FmFile(
				displayName: file.getName(),
				originalName: file.getName(),
				fileType: getFileType(file),
				fileSize: file.length(),
				filestoreLocation: "",
				filestoreName: "",
				linkUrl: ""
			);
			if (!fmFile.save()) {
				fmFile.errors.each {
					log.error(it);
				}
				return;
			}
			fmFile.filestoreLocation = getFilestoreLocation(fmFolder);
			fmFolder.addToFmFiles(fmFile);
			if (!fmFolder.save()) {
				fmFolder.errors.each {
					log.error(it);
				}
				return;
			}
			log.info("File = " + file.getName() + " (" + fmFile.id + ") - New");
		}

		fmFile.filestoreName = Long.toString(fmFile.id, 36).toUpperCase() + "-" + Long.toString(fmFile.fileVersion, 36).toUpperCase() + "." + fmFile.fileType;

		if (!fmFile.save()) {
			fmFile.errors.each {
				log.error(it);
			}
			return;
		 }

		// Use filestore directory based on file's parent study or common directory
		// for files in folders above studies. If directory does not exist, then create it.
		File filestoreDir = new File(filestoreDirectory + fmFile.filestoreLocation);
		if (!filestoreDir.exists()) {
			if (!filestoreDir.mkdirs()) {
				log.error("unable to create filestore " + filestoreDir.getPath());
				return;
			}
		}
		
		// Move file to appropriate filestore directory.
		File filestoreFile = new File(filestoreDirectory + fmFile.filestoreLocation + file.separator + fmFile.filestoreName);
		if (!file.renameTo(filestoreFile)) {
			log.error("unable to move file to " + filestoreFile.getPath());
			return;
		}

		log.info("Moved file to " + filestoreFile.getPath());
		
		// Call file indexer.
		indexFile(fmFile);
		
	}

	/**
	 * Gets type (extension) of specified file.
	 *
	 * @param file
	 * @return
	 */
	def getFileType(File file) {

		String fileType = "";
		int i = file.getName().lastIndexOf('.');
		if (i > -1) {
			fileType = file.getName().substring(i + 1);
		}
		
		return fileType;

	}

	/**
	 * Gets filestore location for specified folder. Files are stored in directories
	 * grouped by their parent study folder tags. If the files are being loaded at
	 * the program level, then a default folder, "0" will be used.
	 *
	 * @param folder
	 * @return
	 */
	def getFilestoreLocation(FmFolder fmFolder) {

		String filestoreLocation;
		
		if (fmFolder.folderLevel == 0) {
			filestoreLocation = "0";
		} else if (fmFolder.folderLevel == 1) {
			filestoreLocation = fmFolder.folderTag;
		} else {
			log.info("folderFullName = " + fmFolder.folderFullName);
			int pos = fmFolder.folderFullName.indexOf("\\", 1);
			log.info("pos 1 = " + pos);
			pos = fmFolder.folderFullName.indexOf("\\", pos + 1);
			log.info("pos 2 = " + pos);
			log.info("find name = " + fmFolder.folderFullName.substring(0, pos));
			FmFolder fmParentFolder = FmFolder.findByFolderFullName(fmFolder.folderFullName.substring(0, pos));	
			if (fmParentFolder == null) {
				log.error("Unable to find folder with folderFullName of " + fmFolder.folderFullName.substring(0, pos));
				filestoreLocation = "0";
			} else {
				filestoreLocation = fmParentFolder.folderTag;
			}
		}

		return File.separator + "fs-" + filestoreLocation;

	}
	
	/**
	 * Indexes file using SOLR.
	 * @param fileId ID of file to be indexed
	 * @return
	 */
	def indexFile(String fileId) {
		
		FmFile fmFile = FmFile.get(fileId);
		if (fmFile == null) {
			log.error("Unable to locate fmFile with id of " + fileId);
			return;
		}
		indexFile(fmFile);
				
	}

	/**
	 * Indexes file using SOLR.
	 * @param fmFile file to be indexed
	 * @return
	 */
	def indexFile(FmFile fmFile) {
		
		try {
			StringBuilder url = new StringBuilder(solrUrl);
			// Use the file's ID as the document ID in SOLR
			url.append("?").append("literal.id=").append(fmFile.id);
			
			// Use the file's name as document name is SOLR
			url.append("&").append("literal.name=").append(URLEncoder.encode(fmFile.originalName, "UTF-8"));
			
			// Get path to actual file in filestore.
			String[] args = [ filestoreDirectory + File.separator + fmFile.filestoreLocation + File.separator + fmFile.filestoreName ] as String[];
			
			// Use SOLR SimplePostTool to manage call to SOLR service.
			SimplePostTool postTool = new SimplePostTool(SimplePostTool.DATA_MODE_FILES, new URL(url.toString()), true,
				null, 0, 0, fileTypes, System.out, true, true, args);
			
			postTool.execute();
		} catch (Exception ex) {
			log.error("Exception while indexing fmFile with id of " + fmFile.id, ex);
		}
		
	}

//	def createFolder(String folderFullName) {
//
//		String[] types = { "Program", "Project", "Assay", "Data" };
//		String[] names = folderFullName.split("\\");
//		String currentFullName = "\\";
//		FmFolder fmFolder;
//
//		for (int level = 0; level < names.length; level++) {
//			currentFullName = currentFullName + names[i] + "\\";
//			fmFolder = FmFolder.findByFolderFullName(currentFullName);
//			if (fmFolder == null) {
//				fmFolder = new FmFolder(
//					folderName: names[level],
//					folderFullName: currentFullName,
//					folderLevel: level,
//					folderType: type[level],
//					activeInd: true,
//					objectUid: currentFullName
//				);
//
//				if (!fmFolder.save(flush:true)) {
//					fmFolder.errors.each {
//						log.error(it);
//					}
//				}
//
//				fmFolder.folderTag = String.format("%8s", Long.toString(fmFolder.id, 36).toUpperCase()).replace(' ', '0');
//				fmFolder.objectUid = String.format("%8s", Long.toString(fmFolder.id, 36).toUpperCase()).replace(' ', '0');
//				if (!fmFolder.save(flush:true)) {
//					fmFolder.errors.each {
//						log.error(it);
//					}
//				}
//			}
//
//			log.info("Folder = " + fmFolder.folderName + " (" + folderId + ")");
//		}
//	
//	}
	
	def getFolderContents(id) {
		
		def parent;
		def folderLevel = 0L;
		if (id != null) {
			parent = FmFolder.get(id)
			folderLevel = parent.folderLevel + 1
		}
		
		def folders = null;
		
		//if (folderMask == null || folderMask.size() > 0) { //If we have an empty list, display no folders
			folders = FmFolder.createCriteria().list {
				if (parent != null) {
					eq('parent', parent)
				}
//						if (folderMask) {
//							'in'('id', folderMask)
//						}
				eq('folderLevel', folderLevel)
				order('folderName', 'asc')
			}
		//}
		 
		return [folders: folders, files: parent?.fmFiles]
	}
	
}