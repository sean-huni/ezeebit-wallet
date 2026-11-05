import liquibase.changelog.*; import liquibase.parser.core.xml.XMLChangeLogSAXParser;
import liquibase.resource.DirectoryResourceAccessor; import liquibase.ChecksumVersion;
import java.io.File;
public class Sum { public static void main(String[] a) throws Exception {
  var ra = new DirectoryResourceAccessor(new File(a[0]));
  var p = new XMLChangeLogSAXParser();
  for (String f : a[1].split(",")) {
    DatabaseChangeLog log = p.parse("db/changelog/2026/10/" + f, new ChangeLogParameters(), ra);
    for (ChangeSet cs : log.getChangeSets()) System.out.println(cs.getId()+" "+cs.generateCheckSum(ChecksumVersion.latest()));
  } } }
