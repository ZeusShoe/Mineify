import { execFile } from 'child_process';
import { promisify } from 'util';
import path from 'path';
import fs from 'fs';

const execFileAsync = promisify(execFile);
const inFlightDownloads = new Map();

export async function downloadAsWav(videoId, downloadDir) {
    const outputPath = path.join(downloadDir, `${videoId}.wav`);
    fs.mkdirSync(downloadDir, { recursive: true });

    // Return immediately if already downloaded
    if (fs.existsSync(outputPath)) {
        if (isValidWav(outputPath)) {
            return outputPath;
        }
        fs.unlinkSync(outputPath);
    }
    if (inFlightDownloads.has(videoId)) {
        return inFlightDownloads.get(videoId);
    }

    const tmpPath = path.join(downloadDir, `${videoId}.part.wav`);
    const promise = execFileAsync('yt-dlp', [
        '-x',
        '--audio-format', 'wav',
        '--js-runtimes', 'node',
        '-o', tmpPath,
        '--no-playlist',
        `https://www.youtube.com/watch?v=${videoId}`
    ], { timeout: 120000 })
        .then(() => {
            if (!fs.existsSync(tmpPath)) {
                throw new Error('WAV download missing output file');
            }
            fs.renameSync(tmpPath, outputPath);
            if (!isValidWav(outputPath)) {
                fs.unlinkSync(outputPath);
                throw new Error('Downloaded file is not a valid WAV');
            }
            return outputPath;
        })
        .finally(() => inFlightDownloads.delete(videoId));

    inFlightDownloads.set(videoId, promise);
    return promise;
}

export function deleteDownload(videoId, downloadDir) {
    const filePath = path.join(downloadDir, `${videoId}.wav`);
    if (fs.existsSync(filePath)) {
        fs.unlinkSync(filePath);
        return true;
    }
    return false;
}

function isValidWav(filePath) {
    try {
        const fd = fs.openSync(filePath, 'r');
        const header = Buffer.alloc(12);
        const read = fs.readSync(fd, header, 0, 12, 0);
        fs.closeSync(fd);
        if (read < 12) {
            return false;
        }
        return header.toString('ascii', 0, 4) === 'RIFF'
            && header.toString('ascii', 8, 12) === 'WAVE';
    } catch {
        return false;
    }
}
