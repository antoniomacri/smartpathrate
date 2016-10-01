// TeXworksScript
// Title: Extract measurements from params
// Author: Antonio Macrì
// Script-Type: standalone
// Context: TeXDocument


function extractMeasurements(text, name) {
  return "\\measurement{" + name + "}{\n" + text
    .replace(new RegExp("\\\\@namedef{" + name + "@(\\d+)}{(\\d+)}", "g"), " $1\t$2 \\\\")
    .replace(/\\@namedef{[a-zA-Z0-9@]+}{.+}\n*/g, "") + 
  "}\n";
}

var text = extractMeasurements(TW.target.text, "wifispeed");
TW.target.selectRange(0, TW.target.text.length);
TW.target.insertText(text);

undefined;
