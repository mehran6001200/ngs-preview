import argparse, json, os, sys
import torch
import onnx

p = argparse.ArgumentParser()
p.add_argument('--voice-changer-server', required=True)
p.add_argument('--input', required=True)
p.add_argument('--output', required=True)
a = p.parse_args()
sys.path.insert(0, os.path.abspath(a.voice_changer_server))

from voice_changer.RVC.onnxExporter.SynthesizerTrnMs768NSFsid_ONNX import SynthesizerTrnMs768NSFsid_ONNX

cpt = torch.load(a.input, map_location='cpu')
print('checkpoint keys:', sorted(cpt.keys()))
print('version:', cpt.get('version'), 'f0:', cpt.get('f0'))
if 'weight' not in cpt or 'config' not in cpt:
    raise RuntimeError('Unsupported RVC checkpoint: weight/config missing')
if cpt.get('version', 'v1') != 'v2':
    raise RuntimeError(f"Expected RVC v2 model, got {cpt.get('version')}")

config = list(cpt['config'])
config[-3] = cpt['weight']['emb_g.weight'].shape[0]
sr_raw = config[-1]
if isinstance(sr_raw, str):
    s = sr_raw.lower().strip()
    if s.endswith('k'):
        sampling_rate = int(float(s[:-1]) * 1000)
    else:
        sampling_rate = int(s)
else:
    sampling_rate = int(sr_raw)
print('sampling_rate:', sampling_rate, 'n_spk:', config[-3])

net = SynthesizerTrnMs768NSFsid_ONNX(*config, is_half=False)
missing, unexpected = net.load_state_dict(cpt['weight'], strict=False)
print('missing:', len(missing), 'unexpected:', len(unexpected))
net.eval().cpu()

T = 64
feats = torch.randn(1, T, 768, dtype=torch.float32)
p_len = torch.tensor([T], dtype=torch.int64)
pitch = torch.randint(5, 255, (1, T), dtype=torch.int64)
pitchf = torch.rand(1, T, dtype=torch.float32) * 300 + 100
sid = torch.tensor([0], dtype=torch.int64)

torch.onnx.export(
    net,
    (feats, p_len, pitch, pitchf, sid),
    a.output,
    dynamic_axes={'feats': {1: 'T'}, 'pitch': {1: 'T'}, 'pitchf': {1: 'T'}},
    do_constant_folding=False,
    opset_version=17,
    verbose=False,
    input_names=['feats', 'p_len', 'pitch', 'pitchf', 'sid'],
    output_names=['audio'],
)

m = onnx.load(a.output)
meta = {
    'application': 'VC_CLIENT',
    'version': '2.1',
    'modelType': 'pyTorchRVCv2',
    'samplingRate': sampling_rate,
    'f0': True,
    'embChannels': 768,
    'embedder': 'hubert',
    'embOutputLayer': 12,
    'useFinalProj': False,
}
prop = m.metadata_props.add()
prop.key = 'metadata'
prop.value = json.dumps(meta, separators=(',', ':'))
onnx.checker.check_model(m)
onnx.save(m, a.output)
print('saved', a.output, 'bytes=', os.path.getsize(a.output), 'metadata=', meta)
