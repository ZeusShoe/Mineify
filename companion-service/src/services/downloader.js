import { execFile } from 'child_process';
import { promisify } from 'util';
import path from 'path';
import fs from 'fs';

const execFileAsync = promisify(execFile);
const inFlightDownloads = new Map();
const YOUTUBE_VIDEO_ID_REGEX = /^[A-Za-z0-9_-]{11}$/;

export function isValidYouTubeVideoId(videoId) {
    return typeof videoId === 'string' && YOUTUBE_VIDEO_ID_REGEX.test(videoId);
}

function requireValidVideoId(videoId) {
    if (!isValidYouTubeVideoId(videoId)) {
        throw new Error('Invalid videoId format');
    }
}

function resolveSafeDownloadPath(downloadDir, videoId, suffix = '.wav') {
    const safeDir = path.resolve(downloadDir);
    const filePath = path.resolve(path.join(safeDir, `${videoId}${suffix}`));
    const relative = path.relative(safeDir, filePath);
    if (relative.startsWith('..') || path.isAbsolute(relative)) {
        throw new Error('Unsafe download path');
    }
    return filePath;
}

export async function downloadAsWav(videoId, downloadDir) {
    requireValidVideoId(videoId);
    const outputPath = resolveSafeDownloadPath(downloadDir, videoId, '.wav');
    const tmpPath = resolveSafeDownloadPath(downloadDir, videoId, '.part.wav');
    fs.mkdirSync(path.resolve(downloadDir), { recursive: true });

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
    if (!isValidYouTubeVideoId(videoId)) {
        return false;
    }
    const filePath = resolveSafeDownloadPath(downloadDir, videoId, '.wav');
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
