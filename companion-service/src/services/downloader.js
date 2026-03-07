import { execFile } from 'child_process';
import { promisify } from 'util';
import path from 'path';
import fs from 'fs';

const execFileAsync = promisify(execFile);
const inFlightDownloads = new Map();

export async function downloadAsWav(videoId, downloadDir) {
    const outputPath = path.join(downloadDir, `${videoId}.mp3`);
    fs.mkdirSync(downloadDir, { recursive: true });

    // Return immediately if already downloaded
    if (fs.existsSync(outputPath)) {
        return outputPath;
    }
    if (inFlightDownloads.has(videoId)) {
        return inFlightDownloads.get(videoId);
    }

    const promise = execFileAsync('yt-dlp', [
        '-x',
        '--audio-format', 'mp3',
        '--audio-quality', '320K',
        '--js-runtimes', 'node',
        '-o', outputPath,
        '--no-playlist',
        `https://www.youtube.com/watch?v=${videoId}`
    ], { timeout: 120000 })
        .then(() => outputPath)
        .finally(() => inFlightDownloads.delete(videoId));

    inFlightDownloads.set(videoId, promise);
    return promise;
}

export function deleteDownload(videoId, downloadDir) {
    const filePath = path.join(downloadDir, `${videoId}.mp3`);
    if (fs.existsSync(filePath)) {
        fs.unlinkSync(filePath);
        return true;
    }
    return false;
}
