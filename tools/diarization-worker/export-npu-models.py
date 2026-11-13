#!/usr/bin/env python3
"""Export masked ERes2NetV2 and verify FP32 equivalence; run in the pinned Linux container."""
import onnx,copy,json,os,numpy as np,onnxruntime as ort
from pathlib import Path
from onnx import helper,TensorProto
import argparse
p=argparse.ArgumentParser();p.add_argument('--source-root',type=Path,default=Path('/models'));p.add_argument('--output',type=Path,default=Path('/out/speaker-eres2netv2-qnn250'));args=p.parse_args()
original=args.source_root/'3dspeaker_eres2netv2_zh.onnx';root=args.output;root.mkdir(parents=True,exist_ok=True)
m=onnx.load(original);static=copy.deepcopy(m)
for d,n in zip(static.graph.input[0].type.tensor_type.shape.dim,[1,1024,80]):d.ClearField('dim_param');d.dim_value=n
static=onnx.shape_inference.infer_shapes(static)
onnx.save(static,root/'embedding-static.onnx')
cut='/fuse34/Add_1_output_0';pre='/Unsqueeze_output_0'
onnx.utils.extract_model(str(root/'embedding-static.onnx'),str(root/'trunk-base.onnx'),[pre],[cut])
t=onnx.load(root/'trunk-base.onnx');sh={v.name:[d.dim_value for d in v.type.tensor_type.shape.dim] for v in [*t.graph.value_info,*t.graph.input,*t.graph.output]}
for node in t.graph.node:
 for i,name in enumerate(node.input):
  if name==pre:node.input[i]='features'
t.graph.input[0].name='features';sh['features']=[1,1,80,1024]
nodes=[];widths=set()
for i,node in enumerate(t.graph.node):
 if node.op_type=='Conv':
  width=sh[node.input[0]][-1];assert width in [1024,512,256,128];widths.add(width)
  masked=f'sound_masked_{i}';nodes.append(helper.make_node('Mul',[node.input[0],f'mask{width}'],[masked],name=f'sound_mask_{i}'));node.input[0]=masked
 nodes.append(node)
del t.graph.node[:];t.graph.node.extend(nodes)
for w in sorted(widths,reverse=True):t.graph.input.append(helper.make_tensor_value_info(f'mask{w}',TensorProto.FLOAT,[1,1,1,w]))
# Rename the output; no old static value_info should constrain the head's time axis.
for node in t.graph.node:
 for i,name in enumerate(node.output):
  if name==cut:node.output[i]='trunk'
t.graph.output[0].name='trunk';onnx.checker.check_model(t);onnx.save(t,root/'embedding-masked-trunk.onnx')
onnx.save(m,root/'embedding-original.onnx')
onnx.utils.extract_model(str(root/'embedding-original.onnx'),str(root/'embedding-head.onnx'),[cut],['embedding'])
h=onnx.load(root/'embedding-head.onnx')
for node in h.graph.node:
 for i,name in enumerate(node.input):
  if name==cut:node.input[i]='trunk'
h.graph.input[0].name='trunk';dims=h.graph.input[0].type.tensor_type.shape.dim
for d,n in zip(dims,[1,1024,10,'S']):
 d.ClearField('dim_param');d.ClearField('dim_value')
 if isinstance(n,str):d.dim_param=n
 else:d.dim_value=n
onnx.checker.check_model(h);onnx.save(h,root/'embedding-head.onnx')
opts=ort.SessionOptions();opts.intra_op_num_threads=2
ref=ort.InferenceSession(str(original),opts,providers=['CPUExecutionProvider']);body=ort.InferenceSession(str(root/'embedding-masked-trunk.onnx'),opts,providers=['CPUExecutionProvider']);head=ort.InferenceSession(str(root/'embedding-head.onnx'),opts,providers=['CPUExecutionProvider'])
rng=np.random.default_rng(20261109);rows=[]
for n in [33,97,199,250,511,598,799,999,1024]:
 x=rng.standard_normal((1,n,80),dtype=np.float32);x-=x.mean(axis=1,keepdims=True)
 padded=np.zeros((1,1,80,1024),np.float32);padded[0,0,:,:n]=x[0].T
 inputs={'features':padded}
 for w in widths:
  valid=(n+(1024//w)-1)//(1024//w);mask=np.zeros((1,1,1,w),np.float32);mask[...,:valid]=1;inputs[f'mask{w}']=mask
 b=body.run(None,inputs)[0][...,:((n+7)//8)].copy();a=ref.run(None,{'x':x})[0];v=head.run(None,{'trunk':b})[0]
 error=float(np.max(np.abs(a-v)));cos=float(np.dot(a.ravel(),v.ravel())/(np.linalg.norm(a)*np.linalg.norm(v)))
 print(n,error,cos,flush=True);rows.append({'frames':n,'maxAbsError':error,'cosine':cos});assert error<.0001 and cos>.999999
(root/'cpu-mask-equivalence.json').write_text(json.dumps({'rows':rows,'passed':True},indent=2))
print('Masked body + original head passes CPU equivalence',flush=True)

for name in ["embedding-static.onnx","trunk-base.onnx","embedding-original.onnx"]:(root/name).unlink(missing_ok=True)
