// Google Apps Script Web App for Albayda Court CSV sync
// 1) Open https://script.google.com/ and create a new project.
// 2) Paste this code into Code.gs.
// 3) Set FILE_ID to your Drive CSV file ID.
// 4) Deploy > New deployment > Type: Web app
//    - Execute as: Me
//    - Who has access: Anyone
// 5) Copy the Web app URL and paste it into the app Admin settings (Drive CSV write URL).

var FILE_ID = '1d_3-M6wTNOcUB1bCOTILh4di6XFGlgCT';

function doGet(e) {
  var file = DriveApp.getFileById(FILE_ID);
  var csv = file.getBlob().getDataAsString('UTF-8');
  return ContentService.createTextOutput(csv).setMimeType(ContentService.MimeType.TEXT);
}

function doPost(e) {
  try {
    var csv = (e && e.postData && e.postData.contents) ? e.postData.contents : '';
    if (!csv || !csv.trim()) {
      return ContentService.createTextOutput(JSON.stringify({ok:false,error:'empty'}))
        .setMimeType(ContentService.MimeType.JSON);
    }
    var file = DriveApp.getFileById(FILE_ID);
    file.setContent(csv);
    return ContentService.createTextOutput(JSON.stringify({ok:true,bytes:csv.length}))
      .setMimeType(ContentService.MimeType.JSON);
  } catch (err) {
    return ContentService.createTextOutput(JSON.stringify({ok:false,error:String(err)}))
      .setMimeType(ContentService.MimeType.JSON);
  }
}
