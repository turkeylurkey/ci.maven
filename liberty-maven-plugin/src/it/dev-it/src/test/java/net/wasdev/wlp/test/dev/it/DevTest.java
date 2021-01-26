/*******************************************************************************
 * (c) Copyright IBM Corporation 2019, 2021.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package net.wasdev.wlp.test.dev.it;

import static org.junit.Assert.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.Scanner;

import org.apache.maven.shared.utils.io.FileUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class DevTest extends BaseDevTest {
   String mpHealthComment = "<!-- <dependency>\n" +
   "        <groupId>io.openliberty.features</groupId>\n" +
   "        <artifactId>mpHealth-1.0</artifactId>\n" +
   "        <type>esa</type>\n" +
   "        <scope>provided</scope>\n" +
   "    </dependency> -->";
   String mpHealth = "<dependency>\n" +
   "        <groupId>io.openliberty.features</groupId>\n" +
   "        <artifactId>mpHealth-1.0</artifactId>\n" +
   "        <type>esa</type>\n" +
   "        <scope>provided</scope>\n" +
   "    </dependency>";
   String mpHealthComment_win = "<!-- <dependency>\r\n" + 
   "        <groupId>io.openliberty.features</groupId>\r\n" + 
   "        <artifactId>mpHealth-1.0</artifactId>\r\n" + 
   "        <type>esa</type>\r\n" + 
   "        <scope>provided</scope>\r\n" + 
   "    </dependency> -->";
   String mpHealth_win = "<dependency>\r\n" +
   "        <groupId>io.openliberty.features</groupId>\r\n" +
   "        <artifactId>mpHealth-1.0</artifactId>\r\n" +
   "        <type>esa</type>\r\n" +
   "        <scope>provided</scope>\r\n" +
   "    </dependency>";

   String invalidDepComment = "<!-- <dependency>\n" + "        <groupId>io.openliberty.features</groupId>\n"
   + "        <artifactId>abcd</artifactId>\n" + "        <version>1.0</version>\n"
   + "    </dependency> -->";
   String invalidDep = "<dependency>\n" + "        <groupId>io.openliberty.features</groupId>\n"
   + "        <artifactId>abcd</artifactId>\n" + "        <version>1.0</version>\n" + "    </dependency>";
   String invalidDepComment_win = "<!-- <dependency>\r\n" + "        <groupId>io.openliberty.features</groupId>\r\n"
   + "        <artifactId>abcd</artifactId>\r\n" + "        <version>1.0</version>\r\n"
   + "    </dependency> -->";
   String invalidDep_win = "<dependency>\r\n" + "        <groupId>io.openliberty.features</groupId>\r\n"
   + "        <artifactId>abcd</artifactId>\r\n" + "        <version>1.0</version>\r\n" + "    </dependency>";

   @BeforeClass
   public static void setUpBeforeClass() throws Exception {
      setUpBeforeClass(null);
   }

   @AfterClass
   public static void cleanUpAfterClass() throws Exception {
      BaseDevTest.cleanUpAfterClass();
   }

   @Test
   /* simple double check. if failure, check parse in ci.common */
   public void verifyJsonHost() throws Exception {
      assertTrue(verifyLogMessageExists("CWWKT0016I", 2000));   // Verify web app code triggered
      //TODO: fix below with correct assertion
      verifyLogMessageExists("http:\\/\\/", 2000);  // Verify escape char seq passes
   }

   @Test
   public void basicTest() throws Exception {
      testModifyJavaFile();
   }

   @Test
   public void configChangeTest() throws Exception {
      // configuration file change
      File srcServerXML = new File(tempProj, "/src/main/liberty/config/server.xml");
      File targetServerXML = new File(targetDir, "/liberty/wlp/usr/servers/defaultServer/server.xml");
      assertTrue(srcServerXML.exists());
      assertTrue(targetServerXML.exists());

      replaceString("</feature>", "</feature>\n" + "    <feature>mpHealth-1.0</feature>", srcServerXML);

      // check for server configuration was successfully updated message
      assertTrue(verifyLogMessageExists("CWWKG0017I", 60000));
      Thread.sleep(2000);
      Scanner scanner = new Scanner(targetServerXML);
      boolean foundUpdate = false;
      try {
         while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            if (line.contains("<feature>mpHealth-1.0</feature>")) {
               foundUpdate = true;
               break;
            }
         }
      } finally {
            scanner.close();
      }
      assertTrue("Could not find the updated feature in the target server.xml file", foundUpdate);
   }

   @Test
   public void resourceFileChangeTest() throws Exception {
      // make a resource file change
      File resourceDir = new File(tempProj, "src/main/resources");
      assertTrue(resourceDir.exists());

      File propertiesFile = new File(resourceDir, "microprofile-config.properties");
      assertTrue(propertiesFile.createNewFile());

      Thread.sleep(2000); // wait for compilation
      File targetPropertiesFile = new File(targetDir, "classes/microprofile-config.properties");
      assertTrue(targetPropertiesFile.exists());
      assertTrue(verifyLogMessageExists("CWWKZ0003I", 100000));

      // delete a resource file
      assertTrue(propertiesFile.delete());
      Thread.sleep(2000);
      assertFalse(targetPropertiesFile.exists());
   }
   
   @Test
   public void testDirectoryTest() throws Exception {
      // create the test directory
      File testDir = new File(tempProj, "src/test/java");
      assertTrue(testDir.mkdirs());

      // creates a java test file
      File unitTestSrcFile = new File(testDir, "UnitTest.java");
      String unitTest = "import org.junit.Test;\n" + "import static org.junit.Assert.*;\n" + "\n"
            + "public class UnitTest {\n" + "\n" + "    @Test\n" + "    public void testTrue() {\n"
            + "        assertTrue(true);\n" + "\n" + "    }\n" + "}";
      Files.write(unitTestSrcFile.toPath(), unitTest.getBytes());
      assertTrue(unitTestSrcFile.exists());

      Thread.sleep(6000); // wait for compilation
      File unitTestTargetFile = new File(targetDir, "/test-classes/UnitTest.class");
      assertTrue(unitTestTargetFile.exists());
      long lastModified = unitTestTargetFile.lastModified();

      // modify the test file
      String str = "// testing";
      BufferedWriter javaWriter = new BufferedWriter(new FileWriter(unitTestSrcFile, true));
      javaWriter.append(' ');
      javaWriter.append(str);

      javaWriter.close();

      Thread.sleep(2000); // wait for compilation
      assertTrue(unitTestTargetFile.lastModified() > lastModified);

      // delete the test file
      assertTrue(unitTestSrcFile.delete());
      Thread.sleep(2000);
      assertFalse(unitTestTargetFile.exists());

   }

   @Test
   public void manualTestsInvocationTest() throws Exception {
      assertTrue(verifyLogMessageExists("To run tests on demand, press Enter.", 2000));

      writer.write("\n");
      writer.flush();

      assertTrue(verifyLogMessageExists("Unit tests finished.", 10000));
      assertTrue(verifyLogMessageExists("Integration tests finished.", 2000));
   }
   
    @Test
    public void invalidDependencyTest() throws Exception {
        // add invalid dependency to pom.xml
        if (isWindows()) {
           replaceString(invalidDepComment_win, invalidDep_win, pom);
        } else {
           replaceString(invalidDepComment, invalidDep, pom);
        }
        assertTrue(verifyLogMessageExists("Unable to resolve artifact: io.openliberty.features:abcd:1.0", 10000));
    }
   
   @Test
   public void resolveDependencyTest() throws Exception {      
      assertTrue(verifyLogMessageExists("Liberty is running in dev mode.", 10000));

      String e = runCmd("id");
      e += "in resolveDependencyTest\n";
      e += "Just verified 'Liberty is running in dev mode', starting log file:\n";
      String actual = readInFile(logFile);
      e += actual+"\n";
      e += "Test of readAllBytes, logFile=\n";
      actual = new String(Files.readAllBytes(logFile.toPath()));
      e += actual+"\n";

      int c1 = searchFile("Source compilation had errors", logFile);
      e += "Before: 'Source compilation had errors' found "+c1+" times.\n";
      // create the HealthCheck class, expect a compilation error
      File systemHealthRes = new File("../resources/SystemHealth.java");
      assertTrue(systemHealthRes.exists());
      File systemHealthSrc = new File(tempProj, "/src/main/java/com/demo/SystemHealth.java");
      File systemHealthTarget = new File(targetDir, "/classes/com/demo/SystemHealth.class");

      FileUtils.copyFile(systemHealthRes, systemHealthSrc);
      assertTrue(systemHealthSrc.exists());
      boolean b1 = verifyLogMessageExists("Source compilation had errors", 200000);
      c1 = searchFile("Source compilation had errors", logFile);
      e += "After: 'Source compilation had errors' found "+c1+" times.\n";
      e += "Found 'Source compilation had errors', 1st occurance and previous line:\n";
      e += readLine0+"\n";
      e += readLine1+"\n";
      assertTrue(e, b1);
      assertFalse(systemHealthTarget.exists());
      
      e += "See the LOG file before updating pom.xml.\n";
      actual = readInFile(logFile);
      e += actual + "\n";
      e += "See the POM file before updating pom.xml.\n";
      actual = readInFile(pom);
      e += actual + "\n";

      // add mpHealth dependency to pom.xml
      boolean b;
      if (isWindows()) {
         e += "isWindows()\n";
         b = replaceString(mpHealthComment_win, mpHealth_win, pom);
      } else {
         e += "is NOT Windows()\n";
         b = replaceString(mpHealthComment, mpHealth, pom);
      }
      e += "replaceString() find something? :"+b+"\n";
      boolean b2 = verifyLogMessageExists("The following features have been installed", 100000, 2);
      e += "Is installed features verified? :"+b2+"\n";
      int c2 = searchFile("The following features have been installed", logFile);
      e += "After changing pom.xml, found 'The following features have been installed' "+c2+" times.\n";
      e += "Found 'The following features have been installed', previous line and current:\n";
      e += readLine0+"\n";
      e += readLine1+"\n";
      e += "Just modified pom.xml, new pom file:\n";
      actual = readInFile(pom);
      e += actual+"\n";
      e += "Just modified pom.xml, new log file: \n";
      actual = readInFile(logFile);
      e += actual+"\n";
      assertTrue(e, b2);
      
      int successfulCount = searchFile("Source compilation was successful.", logFile);
      e += "Number of times 'Source compilation was successful' appears before changing the Java file:"+successfulCount+"\n";
      String str = "// testing";
      BufferedWriter javaWriter = new BufferedWriter(new FileWriter(systemHealthSrc, true));
      javaWriter.append(' ');
      javaWriter.append(str);

      javaWriter.close();

      Thread.sleep(1000); // wait for compilation
      assertTrue(e, verifyLogMessageExists("Source compilation was successful.", 100000, successfulCount + 1));
      Thread.sleep(15000); // wait for compilation
      System.out.println(e);
      assertTrue(e, systemHealthTarget.exists());
   }

}
