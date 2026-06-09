/* eslint-disable max-len */
const { onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { defineSecret }      = require("firebase-functions/params");
const admin                 = require("firebase-admin");
const https                 = require("https");

admin.initializeApp();

// Secrets set via: firebase functions:secrets:set MSG91_AUTH_KEY
//                  firebase functions:secrets:set MSG91_SENDER_ID
const MSG91_AUTH_KEY  = defineSecret("MSG91_AUTH_KEY");
const MSG91_SENDER_ID = defineSecret("MSG91_SENDER_ID");

/**
 * Fires whenever a rider document is updated.
 * Sends a transactional SMS via MSG91 when smsNeeded transitions false → true.
 * Resets smsNeeded to false after attempting the send so duplicate triggers
 * cannot fire even if Firestore delivers the event more than once.
 */
exports.sendDrowsinessAlert = onDocumentUpdated(
  {
    document: "riders/{deviceId}",
    region:   "asia-south1",          // Mumbai — lowest latency for India
    secrets:  [MSG91_AUTH_KEY, MSG91_SENDER_ID],
    timeoutSeconds: 15,
  },
  async (event) => {
    const before = event.data.before.data();
    const after  = event.data.after.data();
    const id     = event.params.deviceId;

    // Only fire on smsNeeded: false/undefined → true transition
    if (!after.smsNeeded || before.smsNeeded === true) {
      return null;
    }

    const riderName = (after.riderName || "the rider").trim();
    const emergency = (after.emergencyContact || "").trim();
    const manager   = (after.managerId        || "").trim();

    // Normalise numbers: strip leading + so MSG91 gets 91XXXXXXXXXX
    const toMobiles = [
      ...new Set(
        [emergency, manager]
          .filter((n) => n.length >= 10)
          .map((n) => n.replace(/^\+/, ""))
      ),
    ];

    if (toMobiles.length === 0) {
      console.warn(`[${id}] smsNeeded=true but no valid recipients configured`);
      await event.data.after.ref.update({ smsNeeded: false });
      return null;
    }

    const message =
      `SmartHelm Alert: ${riderName} is showing signs of drowsiness. ` +
      `Please check in immediately.`;

    const payload = JSON.stringify({
      sender:  MSG91_SENDER_ID.value(),
      route:   "4",          // 4 = transactional — bypasses DND
      country: "91",
      sms: [{ message, to: toMobiles }],
    });

    console.log(`[${id}] Sending SMS to: ${toMobiles.join(", ")}`);

    try {
      const result = await callMsg91(payload, MSG91_AUTH_KEY.value());
      console.log(`[${id}] MSG91 response:`, result);
    } catch (err) {
      console.error(`[${id}] MSG91 send failed:`, err.message);
      // Still reset the flag — don't loop on transient errors
    }

    // Always reset so we don't re-send on the next unrelated Firestore write
    await event.data.after.ref.update({ smsNeeded: false });
    return null;
  }
);

/**
 * Makes a POST request to the MSG91 v5 Send SMS API.
 * Uses Node's built-in https to avoid external dependencies.
 */
function callMsg91(jsonPayload, authKey) {
  return new Promise((resolve, reject) => {
    const options = {
      hostname: "control.msg91.com",
      path:     "/api/v5/sms",
      method:   "POST",
      headers: {
        authkey:          authKey,
        "Content-Type":   "application/json",
        "Content-Length": Buffer.byteLength(jsonPayload),
      },
    };

    const req = https.request(options, (res) => {
      let body = "";
      res.on("data", (chunk) => { body += chunk; });
      res.on("end", () => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve(body);
        } else {
          reject(new Error(`HTTP ${res.statusCode}: ${body}`));
        }
      });
    });

    req.on("error", reject);
    req.write(jsonPayload);
    req.end();
  });
}
