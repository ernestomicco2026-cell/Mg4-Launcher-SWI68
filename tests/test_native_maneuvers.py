"""EN: Check the recovered APK contract against actual launcher code and resources.
IT: Verifica i codici recuperati dall'APK nel codice e nelle risorse del launcher.
"""
import csv
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires the existing build JDK")
class NativeManeuverTest(unittest.TestCase):
    def test_all_native_codes_and_texts_and_unknown_fallback(self):
        with (ROOT / "docs/navigation/native-maneuver-codes.csv").open(encoding="utf-8") as f:
            rows = list(csv.DictReader(f))
        self.assertEqual([int(row["Code"]) for row in rows], list(range(40)))
        resources = sorted(p.stem for p in (ROOT / "app/res/drawable").glob("ic_maneuver_*.xml"))
        for name in resources:
            ET.parse(ROOT / "app/res/drawable" / (name + ".xml"))
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            stub = work / "R.java"
            fields = "\n".join("public static final int %s = %d;" % (name, i + 1) for i, name in enumerate(resources))
            stub.write_text("package com.mg4.launcher; public final class R { public static final class drawable {"
                            + fields + "}}", encoding="utf-8")
            # EN: Preserve all previously accepted icon mappings, especially code 0.
            # IT: Conserva tutte le associazioni gia accettate, soprattutto il codice 0.
            legacy = {0: "straight", 1: "left", 2: "right", 3: "bear_left", 5: "uturn",
                      9: "roundabout_straight", 10: "roundabout_left", 11: "roundabout_right",
                      12: "roundabout_uturn", 13: "roundabout_one", 17: "keep_left", 18: "keep_right",
                      21: "destination", 37: "exit_right"}
            checks = "\n".join("if (ManeuverIcons.drawable(%d) != R.drawable.ic_maneuver_%s) throw new AssertionError(\"Existing code %d changed\");"
                               % (code, name, code) for code, name in legacy.items())
            cases = work / "native-texts.txt"
            cases.write_text("\n".join(row["Exact MG instruction"] for row in rows), encoding="utf-8")
            probe = work / "NativeManeuverProbe.java"
            probe.write_text('''import com.mg4.launcher.R;
import com.mg4.launcher.ui.ManeuverIcons;
import com.mg4.launcher.svc.ManeuverReport;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
public class NativeManeuverProbe {
 public static void main(String[] args) throws Exception {
  for (int code = 0; code < 40; code++) {
   if (ManeuverIcons.drawable(code) == R.drawable.ic_maneuver_unknown) throw new AssertionError("Unmapped native code " + code);
  }
  for (int code : new int[]{-1, -99, 40, 255, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
   if (ManeuverIcons.drawable(code) != R.drawable.ic_maneuver_unknown) throw new AssertionError("Guessed code " + code);
  }
  for (String text : Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8)) {
   if ("unknown".equals(ManeuverReport.instructionType(text))) throw new AssertionError("Unrecognized native text: " + text);
   if (!"unknown".equals(ManeuverReport.instructionType(text + " onto PRIVATE_STREET"))) throw new AssertionError("Private text accepted");
   if (!"unknown".equals(ManeuverReport.instructionType(text + "\\nPRIVATE_STREET"))) throw new AssertionError("Multiline private text accepted");
  }
''' + checks + '''
  if (ManeuverIcons.drawable(5) == ManeuverIcons.drawable(6)) throw new AssertionError("U-turn sides collapsed");
  if (ManeuverIcons.drawable(20) == ManeuverIcons.drawable(22)) throw new AssertionError("Arrival sides collapsed");
  if (ManeuverIcons.drawable(32) != ManeuverIcons.drawable(33) || ManeuverIcons.drawable(34) != ManeuverIcons.drawable(35)) throw new AssertionError("Native entry/merge artwork pairing changed");
  System.out.println("Native codes 0-39, existing mappings, strict text recognition and unknown fallback passed");
 }
}''', encoding="utf-8")
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(work), str(stub),
                            str(ROOT / "app/java/com/mg4/launcher/ui/ManeuverIcons.java"),
                            str(ROOT / "app/java/com/mg4/launcher/svc/ManeuverReport.java"), str(probe)],
                           check=True, capture_output=True)
            subprocess.run(["java", "-cp", str(work), "NativeManeuverProbe", str(cases)], check=True, capture_output=True)


if __name__ == "__main__":
    unittest.main()
