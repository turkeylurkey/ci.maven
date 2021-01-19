/*******************************************************************************
 * (c) Copyright IBM Corporation 2019.
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
        String invalidDepComment = "<!-- <dependency>\n" + "        <groupId>io.openliberty.features</groupId>\n"
                + "        <artifactId>abcd</artifactId>\n" + "        <version>1.0</version>\n"
                + "    </dependency> -->";
        String invalidDep = "<dependency>\n" + "        <groupId>io.openliberty.features</groupId>\n"
                + "        <artifactId>abcd</artifactId>\n" + "        <version>1.0</version>\n" + "    </dependency>";
        replaceString(invalidDepComment, invalidDep, pom);
        assertTrue(verifyLogMessageExists("Unable to resolve artifact: io.openliberty.features:abcd:1.0", 10000));
    }
   
   @Test
   public void resolveDependencyTest() throws Exception {      
      assertTrue(verifyLogMessageExists("Liberty is running in dev mode.", 10000));

      // create the HealthCheck class, expect a compilation error
      File systemHealthRes = new File("../resources/SystemHealth.java");
      assertTrue(systemHealthRes.exists());
      File systemHealthSrc = new File(tempProj, "/src/main/java/com/demo/SystemHealth.java");
      File systemHealthTarget = new File(targetDir, "/classes/com/demo/SystemHealth.class");
      String e = runCmd("id");

      FileUtils.copyFile(systemHealthRes, systemHealthSrc);
      assertTrue(systemHealthSrc.exists());
      
      assertTrue(verifyLogMessageExists("Source compilation had errors", 200000));
      e += "Found Source compilation had errors, previous line and current:\n";
      e += readLine0+"\n";
      e += readLine1+"\n";
      assertFalse(systemHealthTarget.exists());
      e += "assertFalse(systemHealthTarget.exists()) SystemHealth.class not exist yet";
      e += runCmd("cmd /c dir " + systemHealthSrc.getPath());
      e += runCmd("cmd /c dir " + systemHealthTarget.getPath());
      e += runCmd("cmd /c type " + systemHealthSrc.getPath());
      e += "See the log file before adding valid source.\n";
      String actual = Files.readString(logFile.toPath());
      e += actual + "\n";
      // add mpHealth dependency to pom.xml
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
      replaceString(mpHealthComment, mpHealth, pom);
      
      assertTrue(verifyLogMessageExists("The following features have been installed", 100000));
      e += "Found The following features have been installed, previous line and current:\n";
      e += readLine0+"\n";
      e += readLine1+"\n";
      
      String str = "// testing";
      BufferedWriter javaWriter = new BufferedWriter(new FileWriter(systemHealthSrc, true));
      javaWriter.append(' ');
      javaWriter.append(str);

      javaWriter.close();

      Thread.sleep(1000); // wait for compilation
      assertTrue(verifyLogMessageExists("Source compilation was successful.", 100000));
      e += "Found Source compilation was successful, previous line and current:\n";
      e += readLine0+"\n";
      e += readLine1+"\n";
      e += "Found 'Source compilation was successful'. Display the logFile:"+logFile.getPath()+"\n";
      e += runCmd("cmd /c dir " + logFile.getPath());
      e += runCmd("cmd /c type " + logFile.getPath());
      e += "\nSee the log file *after* adding valid source.\n";
      actual = Files.readString(logFile.toPath());
      e += actual+"\n";
      Thread.sleep(45000); // wait for compilation
      e += runCmd("cmd /c dir " + systemHealthSrc.getPath());
      e += runCmd("cmd /c dir " + systemHealthTarget.getPath());
      e += runCmd("cmd /c type " + systemHealthSrc.getPath());
      System.out.println("e="+e);
      assertTrue(e, systemHealthTarget.exists());
   }

}
