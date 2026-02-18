import "dotenv/config";
import express from "express";
import multer from "multer";
import cors from "cors";
import OpenAI from "openai";
import crypto from "crypto";

const app = express();
app.use(cors());
app.use(express.json({ limit: "2mb" }));

const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 8 * 1024 * 1024 }, // 8MB
});

const client = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });

function toDataUrl(buffer, mimeType) {
  return `data:${mimeType};base64,${buffer.toString("base64")}`;
}

function newId(prefix = "") {
  return prefix + crypto.randomBytes(8).toString("hex");
}

/**
 * sessions = Map<sessionId, { images: [{imageId, createdAt, caption, mimeType, dataUrl}], chat: [] }>
 */
const sessions = new Map();

function getSession(sessionId = "default") {
  if (!sessions.has(sessionId)) {
    sessions.set(sessionId, { images: [], chat: [] });
  }
  return sessions.get(sessionId);
}

app.get("/health", (req, res) => res.json({ ok: true }));

/**
 * POST /images
 * Form-data:
 *  - image: file
 *  - sessionId: string (optional)
 */
app.post("/images", upload.single("image"), async (req, res) => {
  try {
    const sessionId = req.body?.sessionId || "default";
    const session = getSession(sessionId);

    if (!req.file) return res.status(400).json({ error: "No image uploaded" });

    const mimeType = req.file.mimetype || "image/jpeg";
    const dataUrl = toDataUrl(req.file.buffer, mimeType);

    const r = await client.responses.create({
      model: "gpt-5",
      input: [
        {
          role: "user",
          content: [
            {
              type: "input_text",
              text:
                "You are a visual assistant for a visually impaired person.\n\n" +
                "Describe what you see in a natural, conversational way like a friendly guide. " +
                "Do NOT use headings or lists. Mention text only if it is clearly readable. " +
                "Mention safety hazards only if clearly visible.\n\n" +
                "If it looks like art/exhibit, give likely context, but if unsure say 'it appears to be' or 'likely'.",
            },
            { type: "input_image", image_url: dataUrl },
          ],
        },
      ],
    });

    const caption = (r.output_text || "").trim();
    if (!caption) return res.status(500).json({ error: "Empty response from model" });

    const imageId = newId("img_");
    session.images.push({
      imageId,
      createdAt: Date.now(),
      caption,
      mimeType,
      dataUrl,
    });

    res.json({
      sessionId,
      imageId,
      caption,
      imageCount: session.images.length,
    });
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: "Failed to store/describe image" });
  }
});

/**
 * Helper: parse user phrases so they don’t need IDs
 * Supports: "first", "second", "third", "last", "previous", "last two", "last three", "all"
 */
function pickImagesByNaturalLanguage({ question, images, currentImageId }) {
  const q = (question || "").toLowerCase();

  if (!images || images.length === 0) return [];

  const current = currentImageId ? images.find((x) => x.imageId === currentImageId) : null;

  // indexes
  const first = images[0];
  const second = images[1];
  const third = images[2];
  const last = images[images.length - 1];
  const prev = images.length >= 2 ? images[images.length - 2] : null;

  const wantsAll = q.includes("all images") || q.includes("compare all") || q.includes("compare everything");
  const wantsLastTwo = q.includes("last two") || q.includes("last 2");
  const wantsLastThree = q.includes("last three") || q.includes("last 3");

  // explicit ordinal refs
  const mentionsFirst = q.includes("first image") || q.includes("1st image") || q.includes("first photo") || q.includes("1st photo");
  const mentionsSecond = q.includes("second image") || q.includes("2nd image") || q.includes("second photo") || q.includes("2nd photo");
  const mentionsThird = q.includes("third image") || q.includes("3rd image") || q.includes("third photo") || q.includes("3rd photo");

  const mentionsLast = q.includes("last image") || q.includes("last photo");
  const mentionsPrev = q.includes("previous image") || q.includes("previous photo") || q.includes("prev image") || q.includes("prev photo");

  // Start with chosen set
  let chosen = [];

  if (wantsAll) {
    // Don't attach every full image (can explode size). We'll attach up to 3 newest,
    // but include captions for all.
    chosen = images.slice(-3);
    return { chosen, useAllCaptions: true };
  }

  if (wantsLastThree) {
    chosen = images.slice(-3);
    return { chosen, useAllCaptions: false };
  }

  if (wantsLastTwo) {
    chosen = images.slice(-2);
    return { chosen, useAllCaptions: false };
  }

  // If user references specific images, include them
  if (mentionsFirst && first) chosen.push(first);
  if (mentionsSecond && second) chosen.push(second);
  if (mentionsThird && third) chosen.push(third);
  if (mentionsLast && last) chosen.push(last);
  if (mentionsPrev && prev) chosen.push(prev);

  // If user says “this image”, include current if available
  const mentionsThis = q.includes("this image") || q.includes("this photo") || q.includes("this one");
  if (mentionsThis && current) chosen.push(current);

  // If user asked to compare and didn't specify, default to last 2 (or last 1 if only one)
  const wantsCompare =
    q.includes("compare") ||
    q.includes("difference") ||
    q.includes("different") ||
    q.includes("similar") ||
    q.includes("same") ||
    q.includes("related") ||
    q.includes("relation");

  if (chosen.length === 0) {
    chosen = wantsCompare ? images.slice(-2) : images.slice(-1);
  }

  // Dedup
  const map = new Map();
  for (const img of chosen) map.set(img.imageId, img);

  // cap attached images to 3 to keep payload safe
  const capped = Array.from(map.values()).slice(-3);

  return { chosen: capped, useAllCaptions: false };
}

/**
 * POST /ask
 * JSON:
 * { sessionId, question, currentImageId? }
 */
app.post("/ask", async (req, res) => {
  try {
    const { sessionId = "default", question, currentImageId } = req.body || {};
    if (!question || typeof question !== "string") {
      return res.status(400).json({ error: "Missing question" });
    }

    const session = getSession(sessionId);
    const allImages = session.images;

    if (!allImages || allImages.length === 0) {
      return res.json({
        sessionId,
        answer: "No images uploaded yet. Please take a photo first.",
        usedImageIds: [],
        imageCount: 0,
      });
    }

    const { chosen, useAllCaptions } = pickImagesByNaturalLanguage({
      question,
      images: allImages,
      currentImageId,
    });

    const captionsContext = (useAllCaptions ? allImages : chosen).map((img, idx) => {
      return `Image ${idx + 1} caption: ${img.caption}`;
    });

    const chatContext = session.chat.slice(-30);

    const r = await client.responses.create({
      model: "gpt-5",
      input: [
        {
          role: "system",
          content: [
            {
              type: "input_text",
              text:
                "You are a visual assistant for a visually impaired person. " +
                "Respond naturally like a helpful guide. No headings/lists. " +
                "If asked to compare, clearly explain similarities and differences. " +
                "If asked who made/painted something, do not claim certainty—describe what style it resembles instead.",
            },
          ],
        },
        ...chatContext.map((m) => ({
          role: m.role,
          content: [{ type: "input_text", text: m.text }],
        })),
        {
          role: "user",
          content: [
            { type: "input_text", text: `User question: ${question}` },
            { type: "input_text", text: `Known captions:\n${captionsContext.join("\n")}` },
            // Attach only selected images (max 3)
            ...chosen.map((img) => ({ type: "input_image", image_url: img.dataUrl })),
          ],
        },
      ],
    });

    const answer = (r.output_text || "").trim();
    if (!answer) return res.status(500).json({ error: "Empty response from model" });

    session.chat.push({ role: "user", text: question, createdAt: Date.now() });
    session.chat.push({ role: "assistant", text: answer, createdAt: Date.now() });

    res.json({
      sessionId,
      answer,
      usedImageIds: chosen.map((x) => x.imageId),
      imageCount: allImages.length,
    });
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: "Failed to answer" });
  }
});

app.get("/sessions/:sessionId/images", (req, res) => {
  const session = getSession(req.params.sessionId);
  res.json({
    sessionId: req.params.sessionId,
    images: session.images.map((x, i) => ({
      index: i + 1,
      imageId: x.imageId,
      createdAt: x.createdAt,
      caption: x.caption,
    })),
    chatTurns: session.chat.length,
  });
});

const port = process.env.PORT || 3000;
app.listen(port, "0.0.0.0", () => {
  console.log(`Backend running on port ${port}`);
});
