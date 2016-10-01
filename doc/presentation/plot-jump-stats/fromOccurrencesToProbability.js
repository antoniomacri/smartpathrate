// TeXworksScript
// Title: From occurrences to probability
// Author: Antonio Macrì
// Script-Type: standalone
// Context: TeXDocument


function extractMeasurements(text, divisor) {
  var re = new RegExp("\\s*(\\d+)\\s+(\\d+)\\s*\\\\\\\\", "g");
  var m, b = "";
  while (m = re.exec(text)) {
    b += " " + m[1] + "\t" + (m[2]/divisor).toFixed(3) + " \\\\\n";
  }
  return b;
}

var divisor = TW.getInt(null, "", "Number of occurrences:", "");
text = extractMeasurements(TW.target.text, divisor);
TW.target.selectRange(0, TW.target.text.length);
TW.target.insertText(text);
undefined;

