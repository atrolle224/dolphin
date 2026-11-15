// CRT Easymode, after EasyMode's CRT shader (GPL): scanlines whose beam widens with
// brightness, and an aperture grille mask. Light: one pass, one texture read.

/*
[configuration]

[OptionRangeFloat]
GUIName = Scanlines (lines on screen)
OptionName = LINES
MinValue = 120
MaxValue = 576
StepAmount = 2
DefaultValue = 240

[OptionRangeFloat]
GUIName = Scanline Strength
OptionName = SCANLINE_STRENGTH
MinValue = 0.0
MaxValue = 1.0
StepAmount = 0.05
DefaultValue = 1.0

[OptionRangeFloat]
GUIName = Scanline Beam Width Min.
OptionName = BEAM_MIN
MinValue = 0.5
MaxValue = 5.0
StepAmount = 0.5
DefaultValue = 1.5

[OptionRangeFloat]
GUIName = Scanline Beam Width Max.
OptionName = BEAM_MAX
MinValue = 0.5
MaxValue = 5.0
StepAmount = 0.5
DefaultValue = 1.5

[OptionRangeFloat]
GUIName = Scanline Brightness Min.
OptionName = BRIGHT_MIN
MinValue = 0.0
MaxValue = 1.0
StepAmount = 0.05
DefaultValue = 0.35

[OptionRangeFloat]
GUIName = Scanline Brightness Max.
OptionName = BRIGHT_MAX
MinValue = 0.0
MaxValue = 1.0
StepAmount = 0.05
DefaultValue = 0.65

[OptionRangeFloat]
GUIName = Mask Strength
OptionName = MASK_STRENGTH
MinValue = 0.0
MaxValue = 1.0
StepAmount = 0.01
DefaultValue = 0.3

[OptionRangeFloat]
GUIName = Mask Size
OptionName = MASK_SIZE
MinValue = 1.0
MaxValue = 10.0
StepAmount = 1.0
DefaultValue = 1.0

[OptionRangeFloat]
GUIName = Gamma Input
OptionName = GAMMA_INPUT
MinValue = 0.1
MaxValue = 5.0
StepAmount = 0.1
DefaultValue = 2.0

[OptionRangeFloat]
GUIName = Gamma Output
OptionName = GAMMA_OUTPUT
MinValue = 0.1
MaxValue = 5.0
StepAmount = 0.1
DefaultValue = 1.8

[OptionRangeFloat]
GUIName = Brightness Boost
OptionName = BRIGHT_BOOST
MinValue = 1.0
MaxValue = 2.0
StepAmount = 0.01
DefaultValue = 1.2

[/configuration]
*/

#define CRT_PI 3.141592653589

void main()
{
  float2 coords = GetCoordinates();
  float3 col = pow(max(Sample().rgb, float3(0.0, 0.0, 0.0)), float3(GetOption(GAMMA_INPUT) / 2.0, GetOption(GAMMA_INPUT) / 2.0, GetOption(GAMMA_INPUT) / 2.0));

  float luma = dot(float3(0.2126, 0.7152, 0.0722), col);
  float bright = (max(col.r, max(col.g, col.b)) + luma) * 0.5;
  float scan_bright = clamp(bright, GetOption(BRIGHT_MIN), GetOption(BRIGHT_MAX));
  float scan_beam = clamp(bright * GetOption(BEAM_MAX), GetOption(BEAM_MIN), GetOption(BEAM_MAX));
  float scan_weight = 1.0 - pow(cos(coords.y * 2.0 * CRT_PI * GetOption(LINES)) * 0.5 + 0.5, scan_beam) * GetOption(SCANLINE_STRENGTH);

  float mask = 1.0 - GetOption(MASK_STRENGTH);
  float dot_no = mod(floor(coords.x * GetTargetResolution().x / GetOption(MASK_SIZE)), 3.0);
  float3 mask_weight;
  if (dot_no < 0.5)
    mask_weight = float3(1.0, mask, mask);
  else if (dot_no < 1.5)
    mask_weight = float3(mask, 1.0, mask);
  else
    mask_weight = float3(mask, mask, 1.0);

  float3 lit = col;
  col *= scan_weight;
  col = mix(col, lit, scan_bright);
  col *= mask_weight;
  col = pow(col, float3(1.0 / GetOption(GAMMA_OUTPUT), 1.0 / GetOption(GAMMA_OUTPUT), 1.0 / GetOption(GAMMA_OUTPUT)));

  SetOutput(float4(col * GetOption(BRIGHT_BOOST), 1.0));
}
