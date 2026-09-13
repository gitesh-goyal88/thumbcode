using System;

namespace ThumbCodeCapture;

/// <summary>
/// THIS FILE IS THE ONLY VENDOR-SPECIFIC PART OF THE BRIDGE.
///
/// It is deliberately unimplemented rather than filled with plausible-looking
/// calls. Mantra, SecuGen and Startek expose different SDKs, and guessing at
/// method signatures would produce code that compiles in your head and throws
/// on the bench. Tell me which reader you have and I will write this against
/// its actual API.
///
/// Shape of the work, whichever vendor it is:
///
///   Mantra MFS100      add lib\MFS100.dll, then MFS100.Init(), AutoCapture()
///                      into a FingerData struct carrying FingerImage,
///                      ISOTemplate, Quality and NFIQ. Convert the raw
///                      greyscale buffer to PNG before returning it.
///
///   SecuGen Hamster    add SGFPLib.dll, then Init(deviceName), OpenDevice(0),
///                      GetImage() into a byte[] of ImageWidth * ImageHeight,
///                      CreateTemplate() for the ISO template.
///
///   Startek FM220      add the FM220 .NET wrapper and follow the same
///                      open / capture / template sequence.
///
/// Two things that bite on all three:
///   - the native DLLs are 32-bit, which is why the csproj pins x86
///   - most SDKs need their runtime DLLs sitting beside the executable, not
///     merely referenced at compile time
///
/// Non-RD capture mode is fine for a self-contained system like this one. It
/// is NOT permitted for Aadhaar authentication, so this path does not extend
/// into a real UIDAI integration later.
/// </summary>
public sealed class VendorReader : IFingerprintReader
{
    public string Name => "Vendor reader (not yet implemented)";

    public bool Open() => false;

    public Capture? CaptureFinger(int timeoutSeconds)
        => throw new NotImplementedException(
            "Fill in VendorReader against your reader's SDK, or run the bridge " +
            "with --mock to use the synthetic reader.");

    public void Dispose() { }
}
