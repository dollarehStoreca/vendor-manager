package ca.dollareh.integration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DollarEhEnricherTest {

    @Test
    void enrichMultoCraft() throws IOException {

        FileInputStream file = new FileInputStream(Paths.get("sample/Multicraft Final Order June 02, 2024.xlsx").toFile());
        Workbook workbook = new XSSFWorkbook(file);

        Sheet sheet = workbook.getSheetAt(0);

        Map<String, Object> productMap ;

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        int i = 0;
        for (Row row : sheet) {
            if (i != 0) {
                String code = row.getCell(1).getStringCellValue();
                productMap = new HashMap<>();

                productMap.put("inventryCode", row.getCell(9).getNumericCellValue());
                productMap.put("discount", row.getCell(10).getNumericCellValue());
                productMap.put("price", row.getCell(11).getNumericCellValue());


                StringBuilder builder = new StringBuilder("workspace/MultiCraft/");

                builder.append(row.getCell(3).getStringCellValue()).append("/");
                builder.append(row.getCell(4).getStringCellValue()).append("/");
                builder.append(row.getCell(5).getStringCellValue()).append("/");

                builder.append(code);
                builder.append(".json");

                Path path = Path.of(builder.toString());

                path.toFile().getParentFile().mkdirs();

                Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(productMap));
            }
            i++;
        }


    }
}
