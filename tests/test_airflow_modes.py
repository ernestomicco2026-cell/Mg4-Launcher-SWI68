"""EN: Check all SAIC outlet combinations without a car.
IT: Verifica tutte le combinazioni SAIC senza auto.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires the existing build JDK")
class AirflowModesTest(unittest.TestCase):
    def test_modes_and_every_outlet_toggle(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            harness = work / "AirflowTest.java"
            harness.write_text('''
import com.mg4.launcher.svc.AirflowModes;
public class AirflowTest {
    private static void equal(int expected, int actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }
    public static void main(String[] args) {
        // EN: Golden values from MG4 Clima's SAIC contract.
        // IT: Valori di riferimento dal protocollo SAIC di MG4 Clima.
        int[] masks = {2, 6, 4, 5, 1, 7, 3};
        int[][] next = {{6, 0, 1}, {5, 2, 0}, {3, 1, 2}, {2, 5, 4},
                        {4, 6, 3}, {1, 3, 6}, {0, 4, 5}};
        int[] outlets = {AirflowModes.WINDSCREEN, AirflowModes.FACE, AirflowModes.FEET};
        for (int mode = 0; mode < 7; mode++) {
            equal(masks[mode], AirflowModes.mask(mode));
            for (int i = 0; i < 3; i++) {
                int toggled = AirflowModes.toggle(mode, outlets[i]);
                equal(next[mode][i], toggled);
                if (AirflowModes.mask(toggled) == 0) throw new AssertionError("All outlets closed");
                if (toggled != mode) equal(mode, AirflowModes.toggle(toggled, outlets[i]));
            }
            equal(mode, AirflowModes.toggle(mode, 0));
            equal(mode, AirflowModes.toggle(mode, 3));
            equal(mode, AirflowModes.toggle(mode, 8));
        }
        for (int invalid : new int[]{-1, 7, 100}) {
            equal(0, AirflowModes.mask(invalid));
            for (int outlet : outlets) equal(invalid, AirflowModes.toggle(invalid, outlet));
        }
    }
}
''', encoding="utf-8")
            subprocess.run(["javac", "--release", "8", "-d", str(work),
                            str(ROOT / "app/java/com/mg4/launcher/svc/AirflowModes.java"),
                            str(harness)], check=True, capture_output=True, text=True)
            subprocess.run(["java", "-cp", str(work), "AirflowTest"],
                           check=True, capture_output=True, text=True)


if __name__ == "__main__":
    unittest.main()
