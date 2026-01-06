#!/usr/bin/env node
/**
 * MCP stdio-to-HTTP Bridge for Anthroid
 *
 * This script bridges Claude CLI's stdio MCP transport to the
 * Anthroid MCP server's HTTP transport.
 *
 * Claude CLI connects to this script via stdio, and this script
 * forwards requests to the HTTP server at localhost:8765/mcp
 */

const http = require('http');
const readline = require('readline');

const MCP_SERVER_HOST = '127.0.0.1';
const MCP_SERVER_PORT = 8765;
const MCP_SERVER_PATH = '/mcp';

// Create readline interface for stdin
const rl = readline.createInterface({
    input: process.stdin,
    output: null,
    terminal: false
});

// Log function that writes to stderr (so it doesn't interfere with MCP protocol on stdout)
function log(message) {
    process.stderr.write(`[mcp-bridge] ${message}\n`);
}

// Send HTTP POST request to MCP server
function sendToMcpServer(jsonData) {
    return new Promise((resolve, reject) => {
        const postData = JSON.stringify(jsonData);

        const options = {
            hostname: MCP_SERVER_HOST,
            port: MCP_SERVER_PORT,
            path: MCP_SERVER_PATH,
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(postData)
            }
        };

        const req = http.request(options, (res) => {
            let data = '';

            res.on('data', (chunk) => {
                data += chunk;
            });

            res.on('end', () => {
                try {
                    const response = JSON.parse(data);
                    resolve(response);
                } catch (e) {
                    log(`Failed to parse response: ${data}`);
                    reject(new Error(`Invalid JSON response: ${data}`));
                }
            });
        });

        req.on('error', (e) => {
            log(`Request error: ${e.message}`);
            reject(e);
        });

        req.write(postData);
        req.end();
    });
}

// Process incoming JSON-RPC message from stdin
async function processMessage(line) {
    try {
        const message = JSON.parse(line);
        log(`Received: ${message.method || 'response'} id=${message.id || 'none'}`);

        // Forward to MCP server
        const response = await sendToMcpServer(message);

        // Write response to stdout
        const responseStr = JSON.stringify(response);
        log(`Sending response for id=${message.id || 'none'}`);
        process.stdout.write(responseStr + '\n');

    } catch (e) {
        log(`Error processing message: ${e.message}`);

        // Send error response if we have an ID
        try {
            const parsed = JSON.parse(line);
            if (parsed.id !== undefined) {
                const errorResponse = {
                    jsonrpc: '2.0',
                    id: parsed.id,
                    error: {
                        code: -32603,
                        message: e.message
                    }
                };
                process.stdout.write(JSON.stringify(errorResponse) + '\n');
            }
        } catch (parseErr) {
            // Can't even parse the original message
            log(`Failed to parse original message: ${line.substring(0, 100)}`);
        }
    }
}

// Handle stdin lines
rl.on('line', (line) => {
    if (line.trim()) {
        processMessage(line);
    }
});

// Handle stdin close (Claude CLI disconnected)
rl.on('close', () => {
    log('stdin closed, exiting');
    process.exit(0);
});

// Handle errors
process.on('uncaughtException', (err) => {
    log(`Uncaught exception: ${err.message}`);
});

log('MCP stdio-to-HTTP bridge started');
log(`Forwarding to http://${MCP_SERVER_HOST}:${MCP_SERVER_PORT}${MCP_SERVER_PATH}`);
