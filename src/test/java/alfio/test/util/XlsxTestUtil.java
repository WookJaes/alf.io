/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.test.util;

import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

public final class XlsxTestUtil {

    private XlsxTestUtil() {}

    public static List<List<String>> rows(byte[] workbook) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(workbook))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    continue;
                }
                var factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes()));
                var result = new ArrayList<List<String>>();
                var rows = document.getElementsByTagName("row");
                for (int i = 0; i < rows.getLength(); i++) {
                    var cells = ((Element) rows.item(i)).getElementsByTagName("c");
                    var values = new ArrayList<String>();
                    for (int j = 0; j < cells.getLength(); j++) {
                        var cell = (Element) cells.item(j);
                        var text = cell.getElementsByTagName("t");
                        values.add(text.getLength() == 0 ? "" : text.item(0).getTextContent());
                    }
                    result.add(values);
                }
                return result;
            }
        }
        throw new IllegalArgumentException("Workbook has no first sheet");
    }
}
