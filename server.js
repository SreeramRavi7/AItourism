const express = require('express');
const multer = require('multer');
const fs = require('fs');
const path = require('path');
const { OpenAI } = require('openai');
require('dotenv').config();

const app = express();
app.use(express.json({ limit: '50mb' }));

const upload = multer({
  dest: 'uploads/',
  limits: { fileSize: 20 * 1024 * 1024 }
});

const openai = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });

const sessions = {};

function getSession(sessionId) {
  if (!sessions[sessionId]) {
    sessions[sessionId] = { images: [], history: [] };
  }
  return sessions[sessionId];
}

// POST /images — Upload image, get AI description in chosen language
app.post('/images', upload.single('image'), async (req, res) => {
  try {
    const { sessionId = 'default', language = 'en', languageName = 'English' } = req.body;
    const file = req.file;
    if (!file) return res.status(400).json({ error: 'No image uploaded' });

    const imageData = fs.readFileSync(file.path);
    const base64Image = imageData.toString('base64');
    const mimeType = file.mimetype || 'image/jpeg';
    const imageId = `img_${Date.now()}`;

    const session = getSession(sessionId);
    session.images.push({ id: imageId, base64: base64Image, mimeType, timestamp: Date.now() });
    if (session.images.length > 10) session.images = session.images.slice(-10);

    let systemPrompt;
    if (language === 'en') {
      systemPrompt = `You are a visual assistant helping a visually impaired person explore the world.

FIRST: If you recognize ANYTHING specific — a landmark, building, artwork, statue, monument, brand, product, logo, animal species, plant, food dish, restaurant, store, book, sign, currency, vehicle model, medication, clothing brand, sports team, celebrity, or any identifiable object — name it and give a brief useful fact. Examples:
- "This is Burrus Hall at Virginia Tech, built in 1936, the main administrative building."
- "This is a Starbucks coffee shop, the entrance is on your right."
- "This looks like a Golden Retriever, a very friendly breed."
- "This is a 20 Euro bill."
- "This appears to be Tylenol, 500mg acetaminophen tablets."
- "This is the Mona Lisa by Leonardo da Vinci, painted around 1503."
- "This is a Toyota Camry, looks like a 2022 model."
- "This is a can of Coca-Cola."

THEN describe:
- What the scene looks like (colors, layout, size, surroundings)
- Any text visible (read it out fully)
- Potential hazards or obstacles (stairs, curbs, crowds, traffic, wet floors)
- Helpful context (doors, pathways, prices, directions, distances)

Keep it concise but informative (3-5 sentences). Be warm like a knowledgeable friend walking beside them.`;
    } else {
      systemPrompt = `You are a visual assistant helping a visually impaired person explore the world.

FIRST: If you recognize ANYTHING specific — a landmark, building, artwork, statue, monument, brand, product, logo, animal species, plant, food dish, restaurant, store, book, sign, currency, vehicle model, medication, clothing brand, sports team, celebrity, or any identifiable object — name it and give a brief useful fact in ${languageName}.

THEN describe:
- What the scene looks like (colors, layout, size, surroundings)
- Any text visible (read it out and translate it to ${languageName} if it's in another language)
- Potential hazards or obstacles (stairs, curbs, crowds, traffic, wet floors)
- Helpful context (doors, pathways, prices, directions, distances)

IMPORTANT: Respond ENTIRELY in ${languageName} (${language}). Keep it concise (3-5 sentences). Be warm like a knowledgeable friend walking beside them.`;
    }

    const response = await openai.chat.completions.create({
      model: 'gpt-4o',
      messages: [
        { role: 'system', content: systemPrompt },
        {
          role: 'user',
          content: [
            { type: 'image_url', image_url: { url: `data:${mimeType};base64,${base64Image}`, detail: 'low' } },
            { type: 'text', text: language === 'en' ? 'What do you see? Identify and describe everything.' : `What do you see? Identify and describe everything. Respond in ${languageName}.` }
          ]
        }
      ],
      max_tokens: 600
    });

    const caption = response.choices[0]?.message?.content || 'Could not describe the image.';
    session.history.push({ role: 'assistant', content: caption, imageId });
    fs.unlink(file.path, () => {});

    res.json({ imageId, caption });
  } catch (err) {
    console.error('Error processing image:', err.message);
    if (req.file) fs.unlink(req.file.path, () => {});
    res.status(500).json({ error: err.message });
  }
});

// POST /ask — Follow-up questions with translation
app.post('/ask', async (req, res) => {
  try {
    const {
      sessionId = 'default',
      question,
      currentImageId,
      inputLanguage = 'en',
      inputLanguageName = 'English',
      outputLanguage = 'en',
      outputLanguageName = 'English'
    } = req.body;

    if (!question) return res.status(400).json({ error: 'No question provided' });

    const session = getSession(sessionId);

    let targetImage = null;
    if (currentImageId) targetImage = session.images.find(img => img.id === currentImageId);
    if (!targetImage && session.images.length > 0) targetImage = session.images[session.images.length - 1];

    let systemPrompt;
    if (outputLanguage === 'en') {
      systemPrompt = `You are a visual assistant for a visually impaired person.
Answer questions about what you see in images. Be specific and helpful.
If you can identify anything in the image (landmark, product, brand, text, species, etc.), name it.
If the user asks to read text, read ALL visible text completely.
If the user asks to translate, translate any foreign text to English.
If the user asks "where am I", identify the location based on visual clues.
If the user's question is in another language, understand it but respond in English.
Be concise and helpful (1-3 sentences).`;
    } else {
      systemPrompt = `You are a visual assistant for a visually impaired person.
Answer questions about what you see in images. Be specific and helpful.
If you can identify anything in the image (landmark, product, brand, text, species, etc.), name it.
If the user asks to read text, read ALL visible text completely.
If the user asks to translate, translate any foreign text to ${outputLanguageName}.
If the user asks "where am I", identify the location based on visual clues.
The user may speak in ${inputLanguageName}. Understand their question regardless of language.
IMPORTANT: Always respond ENTIRELY in ${outputLanguageName} (${outputLanguage}).
Be concise and helpful (1-3 sentences).`;
    }

    const messages = [{ role: 'system', content: systemPrompt }];

    // Include conversation history for context (last 4 exchanges)
    const recentHistory = session.history.slice(-8);
    for (const entry of recentHistory) {
      if (entry.role === 'user') {
        messages.push({ role: 'user', content: entry.content });
      } else if (entry.role === 'assistant') {
        messages.push({ role: 'assistant', content: entry.content });
      }
    }

    if (targetImage) {
      messages.push({
        role: 'user',
        content: [
          { type: 'image_url', image_url: { url: `data:${targetImage.mimeType};base64,${targetImage.base64}`, detail: 'low' } },
          { type: 'text', text: question }
        ]
      });
    } else {
      messages.push({ role: 'user', content: question });
    }

    const response = await openai.chat.completions.create({
      model: 'gpt-4o',
      messages,
      max_tokens: 600
    });

    const answer = response.choices[0]?.message?.content || 'I could not answer that.';
    session.history.push({ role: 'user', content: question });
    session.history.push({ role: 'assistant', content: answer });

    // Keep history manageable
    if (session.history.length > 20) session.history = session.history.slice(-20);

    res.json({ answer });
  } catch (err) {
    console.error('Error answering question:', err.message);
    res.status(500).json({ error: err.message });
  }
});

// GET /health — Health check
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    sessions: Object.keys(sessions).length,
    features: ['image-recognition', 'multilingual', 'translation', 'conversation-history']
  });
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, '0.0.0.0', () => {
  console.log(`\n=== GlassDescribe Server ===`);
  console.log(`Running on port ${PORT}`);
  console.log(`\nEndpoints:`);
  console.log(`  POST /images  — Upload & describe (identifies landmarks, products, text, etc.)`);
  console.log(`  POST /ask     — Follow-up questions (with translation & conversation history)`);
  console.log(`  GET  /health  — Health check`);
  console.log(`\nSupported features:`);
  console.log(`  - Recognizes buildings, landmarks, art, brands, products, animals, food, etc.`);
  console.log(`  - 15 languages with voice input and spoken output`);
  console.log(`  - Translates foreign text in images`);
  console.log(`  - Reads all visible text aloud`);
  console.log(`  - Hazard and obstacle detection`);
  console.log(`  - Conversation history for follow-up questions\n`);
});
