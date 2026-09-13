using System;
using System.Net;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;

namespace ThumbCodeCapture;

/// <summary>
/// A localhost-only HTTP bridge between the issuing portal and the USB reader.
///
///   GET /status    is a reader attached, and which one
///   GET /capture   block for a finger, return a PNG preview plus a template hash
///
/// Bound to 127.0.0.1 on purpose: nothing outside this machine can ask it for a
/// fingerprint. Serve the portal from http://localhost so the browser treats
/// both as secure origins - an https portal calling http://127.0.0.1 trips
/// mixed-content blocking in Chrome, and localhost is also what WebAuthn needs.
/// </summary>
public static class Program
{
    private const string Prefix = "http://127.0.0.1:8137/";
    private static IFingerprintReader _reader = new VendorReader();

    public static async Task Main(string[] args)
    {
        var mock = Array.Exists(args, a => a == "--mock");
        _reader = mock ? new MockReader() : new VendorReader();

        if (!_reader.Open() && !mock)
        {
            Console.WriteLine("No reader opened. Fill in VendorReader.cs, or run with --mock.");
            _reader = new MockReader();
            _reader.Open();
        }

        using var listener = new HttpListener();
        listener.Prefixes.Add(Prefix);
        listener.Start();
        Console.WriteLine($"Capture bridge listening on {Prefix}  reader: {_reader.Name}");
        Console.WriteLine("Leave this window open while issuing documents.");

        while (listener.IsListening)
        {
            var ctx = await listener.GetContextAsync();
            _ = Task.Run(() => Handle(ctx));
        }
    }

    private static void Handle(HttpListenerContext ctx)
    {
        var res = ctx.Response;
        res.AddHeader("Access-Control-Allow-Origin", "*");
        res.AddHeader("Access-Control-Allow-Headers", "content-type");

        if (ctx.Request.HttpMethod == "OPTIONS") { res.StatusCode = 204; res.Close(); return; }

        try
        {
            switch (ctx.Request.Url?.AbsolutePath)
            {
                case "/status":
                    Write(res, new { ok = true, reader = _reader.Name });
                    break;

                case "/capture":
                    var capture = _reader.CaptureFinger(15);
                    if (capture is null)
                    {
                        Write(res, new { error = "No finger detected before the timeout" }, 408);
                        break;
                    }
                    // The image goes back for the clerk to look at and is never
                    // written to disk here. Only templateHash travels onward.
                    Write(res, new
                    {
                        imagePng = Convert.ToBase64String(capture.ImagePng),
                        templateHash = Templates.HashTemplate(capture.IsoTemplate),
                        quality = capture.Quality,
                        reader = capture.DeviceLabel,
                        mock = _reader is MockReader,
                    });
                    break;

                default:
                    Write(res, new { error = "Unknown route" }, 404);
                    break;
            }
        }
        catch (Exception e)
        {
            Write(res, new { error = e.Message }, 500);
        }
    }

    private static void Write(HttpListenerResponse res, object body, int status = 200)
    {
        var bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(body));
        res.StatusCode = status;
        res.ContentType = "application/json";
        res.ContentLength64 = bytes.Length;
        res.OutputStream.Write(bytes);
        res.Close();
    }
}
