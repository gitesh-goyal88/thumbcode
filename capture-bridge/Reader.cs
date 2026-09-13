using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Security.Cryptography;

namespace ThumbCodeCapture;

public record Capture(
    byte[] ImagePng,
    byte[] IsoTemplate,
    int Quality,
    string DeviceLabel);

public interface IFingerprintReader : IDisposable
{
    string Name { get; }
    bool Open();
    /// <summary>Blocks until a finger is presented or the timeout expires.</summary>
    Capture? CaptureFinger(int timeoutSeconds);
}

/// <summary>
/// Used when no vendor SDK is present. Draws a synthetic ridge pattern so the
/// whole issuing flow, including the preview and the template hash, can be
/// demonstrated on a machine with no reader attached.
///
/// It is deterministic per run and clearly labelled, so a mock capture can
/// never be mistaken for a real one further down the pipeline.
/// </summary>
public sealed class MockReader : IFingerprintReader
{
    private readonly Random _rng = new(20260908);

    public string Name => "Mock reader (no hardware)";

    public bool Open() => true;

    public Capture CaptureFinger(int timeoutSeconds)
    {
        const int w = 320, h = 480;
        using var bmp = new Bitmap(w, h, PixelFormat.Format24bppRgb);
        using (var g = Graphics.FromImage(bmp))
        {
            g.Clear(Color.White);
            var cx = w / 2f;
            var cy = h / 2f;
            using var pen = new Pen(Color.FromArgb(40, 40, 40), 2.2f);
            for (var i = 4; i < 60; i++)
            {
                var rx = i * 3.1f;
                var ry = i * 4.3f;
                var wobble = (float)Math.Sin(i * 0.7) * 6f;
                g.DrawEllipse(pen, cx - rx + wobble, cy - ry, rx * 2, ry * 2);
            }
        }

        using var ms = new MemoryStream();
        bmp.Save(ms, ImageFormat.Png);

        var template = new byte[256];
        _rng.NextBytes(template);

        return new Capture(ms.ToArray(), template, 62, Name);
    }

    public void Dispose() { }
}

public static class Templates
{
    /// <summary>
    /// What actually leaves this machine. The image is shown to the clerk and
    /// then dropped; only this hash is sent to the server, so a database dump
    /// yields nothing that can be turned back into a fingerprint.
    /// </summary>
    public static string HashTemplate(byte[] isoTemplate)
        => Convert.ToHexString(SHA256.HashData(isoTemplate)).ToLowerInvariant();
}
