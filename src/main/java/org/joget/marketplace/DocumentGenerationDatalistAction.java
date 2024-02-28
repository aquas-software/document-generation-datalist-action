package org.joget.marketplace;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.awt.Dimension;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFTable.XWPFBorderType;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.service.AppPluginUtil;
import org.joget.apps.app.service.AppResourceUtil;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.datalist.model.DataList;
import org.joget.apps.datalist.model.DataListActionDefault;
import org.joget.apps.datalist.model.DataListActionResult;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.apps.form.service.FileUtil;
import org.joget.commons.util.LogUtil;
import org.joget.workflow.util.WorkflowUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 *
 * @author Maxson
 */
public class DocumentGenerationDatalistAction extends DataListActionDefault {

    private final static String MESSAGE_PATH = "message/form/DocumentGenerationDatalistAction";

    @Override
    public String getName() {
        return "Document Generation Datalist Action";
    }

    @Override
    public String getVersion() {
        return "8.0.2";
    }

    @Override
    public String getDescription() {
        return AppPluginUtil.getMessage("org.joget.marketplace.DocumentGenerationDatalistAction.pluginDesc", getClassName(), MESSAGE_PATH);
    }

    @Override
    public String getLinkLabel() {
        return getPropertyString("buttonLabel");
    }

    @Override
    public String getHref() {
        return getPropertyString("href");
    }

    @Override
    public String getTarget() {
        return "post";
    }

    @Override
    public String getHrefParam() {
        return getPropertyString("hrefParam");
    }

    @Override
    public String getHrefColumn() {
        String recordIdColumn = getPropertyString("recordIdColumn");
        if ("id".equalsIgnoreCase(recordIdColumn) || recordIdColumn.isEmpty()) {
            return getPropertyString("hrefColumn");
        } else {
            return recordIdColumn;
        }
    }

    @Override
    public String getConfirmation() {
        return getPropertyString("confirmation");
    }

    @Override
    public String getLabel() {
        return AppPluginUtil.getMessage("org.joget.marketplace.DocumentGenerationDatalistAction.pluginLabel", getClassName(), MESSAGE_PATH);
    }

    @Override
    public String getClassName() {
        return getClass().getName();
    }

    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClassName(), "/properties/form/documentGenerationDatalistAction.json", null, true, MESSAGE_PATH);
    }

    @Override
    public DataListActionResult executeAction(DataList dataList, String[] rowKeys) {

        HttpServletRequest request = WorkflowUtil.getHttpServletRequest();
        if (request != null && !"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }

        if (rowKeys != null && rowKeys.length > 0) {
            try {
                HttpServletResponse response = WorkflowUtil.getHttpServletResponse();

                if (rowKeys.length == 1) {
                    generateSingleFile(request, response, rowKeys[0]);
                } else {
                    generateMultipleFile(request, response, rowKeys);

                }
            } catch (Exception e) {
                LogUtil.error(getClassName(), e, "Failed to generate word file");
            }
        }
        return null;
    }

    protected void replacePlaceholderInParagraphs(Map<String, String> dataParams, XWPFDocument xwpfDocument) {
        for (Map.Entry<String, String> entry : dataParams.entrySet()) {
            for (XWPFParagraph paragraph : xwpfDocument.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.isEmpty() && text.contains(entry.getKey())) {
                    text = text.replace("${" + entry.getKey() + "}", entry.getValue());

                    for (int i = paragraph.getRuns().size() - 1; i >= 0; i--) {
                        paragraph.removeRun(i);
                    }

                    // if value is json
                    if (text.contains("{") || text.contains("}") || text.contains("[") || text.contains("]")) {
                        replacePlaceholderInJSON(text, xwpfDocument);
                    } else {
                        XWPFRun newRun = paragraph.createRun();
                        newRun.setText(text);
                    }
                }
            }
        }
    }

    protected void replacePlaceholderInJSON(String text, XWPFDocument xwpfDocument) {
        JsonArray jsonArray = JsonParser.parseString(text).getAsJsonArray();

        int colIndex = 0;
        int rowIndex = 0;
        if (getPropertyString("gridIncludeHeader").equals("true")) {
            rowIndex = 1;
        }

        LinkedHashSet<String> jsonKeyList = new LinkedHashSet<>();
        ArrayList<String> jsonValueList = new ArrayList<>();
        for (int i = 0; i < jsonArray.size(); i++) {
            JsonObject jsonObject = jsonArray.get(i).getAsJsonObject();
            Set<Map.Entry<String, JsonElement>> entrySet = jsonObject.entrySet();

            for (Map.Entry<String, JsonElement> entryJson : entrySet) {
                String fieldName = entryJson.getKey();
                JsonElement fieldValue = entryJson.getValue();

                // Exclude "", "id" and "__UNIQUEKEY__"
                if (!fieldName.equals("") && !fieldName.equals("id") && !fieldName.equals("__UNIQUEKEY__")) {
                    jsonKeyList.add(fieldName);
                    jsonValueList.add(fieldValue.getAsString());
                }
            }
        }

        XWPFTable table = null;
        if (getPropertyString("gridDirection").equals("horizontal")) {
            table = createEmptyGridTable(jsonKeyList.size(), (jsonValueList.size() / jsonKeyList.size()) + rowIndex, xwpfDocument);
          
        } else if(getPropertyString("gridDirection").equals("vertical")){
             table = createEmptyGridTable((jsonValueList.size() / jsonKeyList.size()) + rowIndex, jsonKeyList.size(), xwpfDocument);
        }

        // table header
        if (getPropertyString("gridIncludeHeader").equals("true")) {
            rowIndex = 0;
            for (String jsonKey : jsonKeyList) {
                if (getPropertyString("gridDirection").equals("horizontal")){
                    table.getRow(rowIndex).getCell(0).setText(jsonKey);
                } else if (getPropertyString("gridDirection").equals("vertical")){
                    table.getRow(0).getCell(rowIndex).setText(jsonKey);
                }
               
                rowIndex++;
            }
            colIndex = 1;
        }

        // table value
        rowIndex = 0;
        for (String jsonValue : jsonValueList) {
            if (getPropertyString("gridDirection").equals("horizontal")) {
                table.getRow(rowIndex).getCell(colIndex).setText(jsonValue);
            } else if (getPropertyString("gridDirection").equals("vertical")) {
                table.getRow(colIndex).getCell(rowIndex).setText(jsonValue);
            }

            if ((jsonKeyList.size() - 1) == rowIndex) {
                rowIndex = 0;
                colIndex++;
            } else {
                rowIndex++;
            }
        }
    }

    protected void replacePlaceholderInTables(Map<String, String> dataParams, XWPFDocument xwpfDocument) {
        for (Map.Entry<String, String> entry : dataParams.entrySet()) {
            for (XWPFTable xwpfTable : xwpfDocument.getTables()) {
                for (XWPFTableRow xwpfTableRow : xwpfTable.getRows()) {
                    for (XWPFTableCell xwpfTableCell : xwpfTableRow.getTableCells()) {
                        for (XWPFParagraph xwpfParagraph : xwpfTableCell.getParagraphs()) {
                            String text = xwpfParagraph.getText();
                            if (text != null && !text.isEmpty() && text.contains(entry.getKey())) {
                                text = text.replace("${" + entry.getKey() + "}", entry.getValue());

                                for (int i = xwpfParagraph.getRuns().size() - 1; i >= 0; i--) {
                                    xwpfParagraph.removeRun(i);
                                }
                                XWPFRun newRun = xwpfParagraph.createRun();
                                newRun.setText(text);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Gets image dimensions for given file 
     * @param imgFile image file
     * @return dimensions of image
     * @throws IOException if the file is not a known image
     */
    public static Dimension getImageDimension(File imgFile) throws IOException {
        int pos = imgFile.getName().lastIndexOf(".");
        if (pos == -1)
            throw new IOException("No extension for file: " + imgFile.getAbsolutePath());
        String suffix = imgFile.getName().substring(pos + 1);
        Iterator<ImageReader> iter = ImageIO.getImageReadersBySuffix(suffix);
        while(iter.hasNext()) {
            ImageReader reader = iter.next();
            try {
                ImageInputStream stream = new FileImageInputStream(imgFile);
                reader.setInput(stream);
                int width = reader.getWidth(reader.getMinIndex());
                int height = reader.getHeight(reader.getMinIndex());
                return new Dimension(width, height);
            } catch (IOException e) {
                LogUtil.error("getImageDimension", e, "Error reading: " + imgFile.getAbsolutePath());
            } finally {
                reader.dispose();
            }
        }
        throw new IOException("Not a known image file: " + imgFile.getAbsolutePath());
    }

    protected void replaceImageInParagraph(Map<String, String> dataParams, XWPFDocument xwpfDocument, String row) {

        for (Map.Entry<String, String> entry : dataParams.entrySet()) {
            for (XWPFParagraph paragraph : xwpfDocument.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.isEmpty() && text.contains(entry.getValue())) {
                    if (isImageValue(text)) {
                        try {
                            AppDefinition appDef = AppUtil.getCurrentAppDefinition();
                            String formDef = getPropertyString("formDefId");
                            AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
                            String tableName = appService.getFormTableName(appDef, formDef);
                            File file = FileUtil.getFile(text, tableName, row);
                            FileInputStream fileInputStream = new FileInputStream(file);
                            for (int i = paragraph.getRuns().size() - 1; i >= 0; i--) {
                                paragraph.removeRun(i);
                            }
                            
                            double width;
                            double height;

                            String widthPixelsString = getPropertyString("imageWidthPixels");
                            String heightPixelsString = getPropertyString("imageHeightPixels");
                            int widthPixels = Integer.parseInt(0 + widthPixelsString);
                            int heightPixels = Integer.parseInt(0 + heightPixelsString);

                            String widthCmString = getPropertyString("imageWidthCentimeters").replaceAll(",",".");
                            String heightCmString = getPropertyString("imageHeightCentimeters").replaceAll(",",".");
                            double widthCm = Double.parseDouble(0 + widthCmString);
                            double heightCm = Double.parseDouble(0 + heightCmString);

                            if (widthPixels > 0 && heightPixels > 0) {
                                //use pixel-values
                                width = widthPixels * Units.EMU_PER_PIXEL;
                                height = heightPixels * Units.EMU_PER_PIXEL;
                            } else if ((int) widthCm > 0 && (int) heightCm > 0) {
                                //use cm values
                                width = widthCm * Units.EMU_PER_CENTIMETER;
                                height = heightCm * Units.EMU_PER_CENTIMETER;
                            } else {
                                //use image dimensions from file
                                Dimension imageDimensions = getImageDimension(file);
                                width = imageDimensions.getWidth() * Units.EMU_PER_PIXEL;
                                height = imageDimensions.getHeight() * Units.EMU_PER_PIXEL;
                            };

                            LogUtil.info(this.getClassName(), "width" + width);
                            LogUtil.info(this.getClassName(), "height" + height);

                            XWPFRun newRun = paragraph.createRun();
                            newRun.addPicture(fileInputStream, Document.PICTURE_TYPE_PNG, row + "_image", (int) width, (int) height);
                            fileInputStream.close();
                        } catch (IOException | InvalidFormatException e) {
                            LogUtil.error(getClassName(), e, "Failed to generate word file");
                        }
                    }
                }
            }
        }
    }

    protected void replaceImageInTable(Map<String, String> dataParams, XWPFDocument xwpfDocument, String row) {
        for (Map.Entry<String, String> entry : dataParams.entrySet()) {
            for (XWPFTable xwpfTable : xwpfDocument.getTables()) {
                for (XWPFTableRow xwpfTableRow : xwpfTable.getRows()) {
                    for (XWPFTableCell xwpfTableCell : xwpfTableRow.getTableCells()) {
                        for (XWPFParagraph xwpfParagraph : xwpfTableCell.getParagraphs()) {
                            String text = xwpfParagraph.getText();
                            if (text != null && !text.isEmpty() && text.contains(entry.getValue())) {
                                if (isImageValue(entry.getValue())) {
                                    try {
                                        AppDefinition appDef = AppUtil.getCurrentAppDefinition();
                                        String formDef = getPropertyString("formDefId");
                                        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
                                        String tableName = appService.getFormTableName(appDef, formDef);
                                        File file = FileUtil.getFile(entry.getValue(), tableName, row);
                                        FileInputStream fileInputStream = new FileInputStream(file);
                                        for (int i = xwpfParagraph.getRuns().size() - 1; i >= 0; i--) {
                                            xwpfParagraph.removeRun(i);
                                        }
                                        double width;
                                        double height;

                                        String widthPixelsString = getPropertyString("imageWidthPixels");
                                        String heightPixelsString = getPropertyString("imageHeightPixels");
                                        int widthPixels = Integer.parseInt(0 + widthPixelsString);
                                        int heightPixels = Integer.parseInt(0 + heightPixelsString);

                                        String widthCmString = getPropertyString("imageWidthCentimeters").replaceAll(",",".");
                                        String heightCmString = getPropertyString("imageHeightCentimeters").replaceAll(",",".");
                                        double widthCm = Double.parseDouble(0 + widthCmString);
                                        double heightCm = Double.parseDouble(0 + heightCmString);

                                        if (widthPixels > 0 && heightPixels > 0) {
                                            //use pixel-values
                                            width = widthPixels * Units.EMU_PER_PIXEL;
                                            height = heightPixels * Units.EMU_PER_PIXEL;
                                        } else if ((int) widthCm > 0 && (int) heightCm > 0) {
                                            //use cm values
                                            width = widthCm * Units.EMU_PER_CENTIMETER;
                                            height = heightCm * Units.EMU_PER_CENTIMETER;
                                        } else {
                                            //use image dimensions from file
                                            Dimension imageDimensions = getImageDimension(file);
                                            width = imageDimensions.getWidth();
                                            height = imageDimensions.getHeight();
                                        };
                                        
                                        XWPFRun newRun = xwpfParagraph.createRun();
                                        newRun.addPicture(fileInputStream, Document.PICTURE_TYPE_JPEG, row + "_image", (int) width, (int) height);
                                        fileInputStream.close();
                                    } catch (IOException | InvalidFormatException e) {
                                        LogUtil.error(getClassName(), e, "Failed to generate word file");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    }

    private boolean isImageValue(String value) {
        if (value.toLowerCase().endsWith(".jpg") || value.toLowerCase().endsWith(".png") || value.toLowerCase().endsWith(".jpeg")) {
            return true;
        } else {
            return false;
        }
    }

    protected XWPFTable createEmptyGridTable(int rows, int cols, XWPFDocument xwpfDocument) {
        XWPFTable table = xwpfDocument.createTable(rows, cols);
        table.getCTTbl().addNewTblGrid().addNewGridCol().setW(BigInteger.valueOf(Integer.parseInt(getPropertyString("gridWidth"))));
        for (int i = 1; i < cols; i++){
            table.getCTTbl().getTblGrid().addNewGridCol().setW(BigInteger.valueOf(Integer.parseInt(getPropertyString("gridWidth"))));
        }
      
        table.setInsideHBorder(XWPFBorderType.THICK, 5, 0, "000000");
        table.setInsideVBorder(XWPFBorderType.THICK, 5, 0, "000000");
        table.setTopBorder(XWPFBorderType.THICK, 5, 0, "000000");
        table.setBottomBorder(XWPFBorderType.THICK, 5, 0, "000000");
        table.setLeftBorder(XWPFBorderType.THICK, 5, 0, "000000");
        table.setRightBorder(XWPFBorderType.THICK, 5, 0, "000000");

        return table;
    }

    protected File getTempFile() throws IOException {
        String fileHashVar = getPropertyString("templateFile");
        String templateFilePath = AppUtil.processHashVariable(fileHashVar, null, null, null);
        Path filePath = Paths.get(templateFilePath);
        String fileName = filePath.getFileName().toString();
        AppDefinition appDef = AppUtil.getCurrentAppDefinition();
        File file = AppResourceUtil.getFile(appDef.getAppId(), String.valueOf(appDef.getVersion()), fileName);
        //Validation
        if (file.exists()) {
            return file;
        } else {
            return null;
        }
    }

    public static ArrayList<String> getAllMatches(String text, String regex, boolean useCustomGroup) {
        ArrayList<String> matches = new ArrayList<String>();
        Matcher m = Pattern.compile("(?=(" + regex + "))").matcher(text);
        int groupId = useCustomGroup ? 2 : 1;
        while(m.find()) {
            matches.add(m.group(groupId));
        }
        return matches;
    }

    protected void generateSingleFile(HttpServletRequest request, HttpServletResponse response, String row) {

        AppDefinition appDef = AppUtil.getCurrentAppDefinition();
        String formDef = getPropertyString("formDefId");
        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");

        FormRowSet formRowSet = appService.loadFormData(appDef.getAppId(), appDef.getVersion().toString(), formDef, row);

        try {
            File tempFile = getTempFile();
            InputStream fInputStream = new FileInputStream(tempFile);

            //Create a XWPFDocument object
            XWPFDocument apachDoc = new XWPFDocument(fInputStream);
            XWPFWordExtractor extractor = new XWPFWordExtractor(apachDoc);

            //Extracted Text stored in a String 
            String text = extractor.getText();
            extractor.close();

            //File Text Array & ArrayList (After regex)
            //String[] textArr;
            ArrayList<String> textArrayList = new ArrayList<String>();

            //Remove all whitespaces in extracted text
            // textArr = text.split("\\s+");
            // for (String x : textArr) {
            //     if (x.startsWith("${") && x.endsWith("}")) {
            //         textArrayList.add(x.substring(2, x.length() - 1));
            //     }
            // }

            //use regex instead of text.split so matches dont have to be enclosed by whitespaces
            textArrayList = getAllMatches(text, "\\$\\{([^$\\{\\}]+)\\}", true);

            //Perform Matching Operation
            Map<String, String> matchedMap = new HashMap<>();
            if (formRowSet != null && !formRowSet.isEmpty()) {
                for (String key : textArrayList) {
                    for (FormRow r : formRowSet) {
                        //The keyset of the formrow
                        Set<Object> formSet = r.keySet();

                        //Matching operation => Check if form key match with template key
                        for (Object formKey : formSet) {
                            //if text follows format "json[1].jsonKey", translate json array format
                            Pattern pattern = Pattern.compile("([a-zA-Z]+)\\[(\\d+)\\]\\.(.+)");
                            Matcher matcher = pattern.matcher(key);
                            
                            if (matcher.matches()) {
                                String jsonName = matcher.group(1);
                                String rowNum = matcher.group(2);
                                String jsonKey = matcher.group(3);
                      
                                if (formKey.toString().equals(jsonName)){
                                    String jsonString = r.getProperty(jsonName);
                                    JSONArray jsonArray = new JSONArray(jsonString);

                                    if (jsonArray.length() > Integer.parseInt(rowNum)) {
                                        JSONObject jsonObject = jsonArray.getJSONObject(Integer.parseInt(rowNum));
                                        String jsonValue = jsonObject.getString(jsonKey);
                                        matchedMap.put(key, jsonValue);
                                    }
                                }
                            }
                         
                            if (formKey.toString().equals(key)) {
                                matchedMap.put(formKey.toString(), r.getProperty(key));
                            }
                        }
                    }
                }
            }

            //Methods to replace all selected datalist data field with template file placeholder variables respectively
            replacePlaceholderInParagraphs(matchedMap, apachDoc);
            replacePlaceholderInTables(matchedMap, apachDoc);
            replaceImageInParagraph(matchedMap, apachDoc, row);
            replaceImageInTable(matchedMap, apachDoc, row);

            String fileName = getPropertyString("fileName");
            //String fileName = row + ".docx";

            writeResponseSingle(request, response, apachDoc, fileName, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        } catch (Exception e) {
            LogUtil.error(this.getClassName(), e, e.toString());
        }
    }

    //Generate word file for multiple datalist row
    protected void generateMultipleFile(HttpServletRequest request, HttpServletResponse response, String[] rows) throws IOException, ServletException {

        //ArrayList of XWPFDocument
        ArrayList<XWPFDocument> documents = new ArrayList<>();

        for (String row : rows) {
            AppDefinition appDef = AppUtil.getCurrentAppDefinition();
            String formDef = getPropertyString("formDefId");
            AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
            //To get whole row of the datalist
            FormRowSet formRowSet = appService.loadFormData(appDef.getAppId(), appDef.getVersion().toString(), formDef, row);

            try {

                File tempFile = getTempFile();
                InputStream fInputStream = new FileInputStream(tempFile);

                //XWPFDocument 
                XWPFDocument apachDoc = new XWPFDocument(fInputStream);
                XWPFWordExtractor extractor = new XWPFWordExtractor(apachDoc);

                //Extracted Text stored in String 
                String text = extractor.getText();
                extractor.close();

                //File Text Array & ArrayList (After regex)
                //String[] textArr;
                ArrayList<String> textArrayList = new ArrayList<String>();

                //Remove all whitespaces in extracted text
                // textArr = text.split("\\s+");
                // for (String x : textArr) {
                //     if (x.startsWith("${") && x.endsWith("}")) {
                //         textArrayList.add(x.substring(2, x.length() - 1));
                //     }
                // }

                //use regex instead of text.split so matches dont have to be enclosed by whitespaces
                textArrayList = getAllMatches(text, "\\$\\{([^$\\{\\}]+)\\}", true);

                //Matching Operation
                Map<String, String> matchedMap = new HashMap<String, String>();
                if (formRowSet != null && !formRowSet.isEmpty()) {
                    for (String key : textArrayList) {
                        for (FormRow r : formRowSet) {
                            Set<Object> formSet = r.keySet();
                            for (Object formKey : formSet) {
                                //if text follows format "json[1].jsonKey", translate json array format
                                Pattern pattern = Pattern.compile("([a-zA-Z]+)\\[(\\d+)\\]\\.(.+)");
                                Matcher matcher = pattern.matcher(key);
                                
                                if (matcher.matches()) {
                                    String jsonName = matcher.group(1);
                                    String rowNum = matcher.group(2);
                                    String jsonKey = matcher.group(3);
                        
                                    if (formKey.toString().equals(jsonName)){
                                        String jsonString = r.getProperty(jsonName);
                                        JSONArray jsonArray = new JSONArray(jsonString);

                                        if (jsonArray.length() > Integer.parseInt(rowNum)) {
                                            JSONObject jsonObject = jsonArray.getJSONObject(Integer.parseInt(rowNum));
                                            String jsonValue = jsonObject.getString(jsonKey);
                                            matchedMap.put(key, jsonValue);
                                        }
                                    }
                                }

                                if (formKey.toString().equals(key)) {
                                    matchedMap.put(formKey.toString(), r.getProperty(key));
                                }
                            }
                        }
                    }
                }
                replacePlaceholderInParagraphs(matchedMap, apachDoc);
                replacePlaceholderInTables(matchedMap, apachDoc);
                replaceImageInParagraph(matchedMap, apachDoc, row);
                replaceImageInTable(matchedMap, apachDoc, row);
                documents.add(apachDoc);
            } catch (Exception e) {
                LogUtil.error(this.getClassName(), e, e.toString());
            }
        }
        writeResponseMulti(request, response, documents, rows);
    }

    protected void writeResponseMulti(HttpServletRequest request, HttpServletResponse response, ArrayList<XWPFDocument> apachDocs, String[] rows) throws IOException, ServletException {
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=WordFiles.zip");
        try ( ZipOutputStream zipOutputStream = new ZipOutputStream(response.getOutputStream())) {
            for (int i = 0; i < apachDocs.size(); i++) {
                ZipEntry zipEntry = new ZipEntry(rows[i] + ".docx");
                zipOutputStream.putNextEntry(zipEntry);
                apachDocs.get(i).write(zipOutputStream);
                zipOutputStream.closeEntry();
            }
            zipOutputStream.flush();

        } catch (Exception e) {
            LogUtil.error(this.getClassName(), e, e.toString());
        }
    }

    protected void writeResponseSingle(HttpServletRequest request, HttpServletResponse response, XWPFDocument apachDoc, String fileName, String contentType) throws IOException, ServletException {
        ServletOutputStream outputStream = response.getOutputStream();
        try {
            String name = URLEncoder.encode(fileName, "UTF8").replaceAll("\\+", "%20");
            response.setHeader("Content-Disposition", "attachment;filename=" + name + ";filename*=UTF-8''" + name);
            response.setContentType(contentType + "; charset=UTF-8");
            apachDoc.write(outputStream);

        } finally {
            apachDoc.close();
            outputStream.flush();
            outputStream.close();
        }
    }
}
