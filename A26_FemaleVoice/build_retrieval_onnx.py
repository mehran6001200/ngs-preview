import argparse
from pathlib import Path

import faiss
import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper


def extract_ivf_centroids(index_path: str) -> np.ndarray:
    index = faiss.read_index(index_path)
    ivf = faiss.extract_index_ivf(index)
    quantizer = ivf.quantizer
    centroids = np.stack([quantizer.reconstruct(i) for i in range(ivf.nlist)]).astype(np.float32)
    if centroids.ndim != 2 or centroids.shape[1] != index.d:
        raise RuntimeError(f"bad centroid shape {centroids.shape}, d={index.d}")
    print(f"index ntotal={index.ntotal} nlist={ivf.nlist} d={index.d}")
    print(f"centroids={centroids.shape} bytes={centroids.nbytes}")
    return centroids


def make_model(centroids: np.ndarray, output: str, topk: int = 8) -> None:
    k_centroids, channels = centroids.shape
    if topk > k_centroids:
        topk = k_centroids

    cent_t = centroids.T.copy()
    cent_norm = np.sum(centroids * centroids, axis=1, dtype=np.float32).reshape(1, 1, -1)

    inputs = [helper.make_tensor_value_info("feats", TensorProto.FLOAT, [1, "T", channels])]
    outputs = [helper.make_tensor_value_info("retrieved", TensorProto.FLOAT, [1, "T", channels])]

    initializers = [
        numpy_helper.from_array(centroids, name="centroids"),
        numpy_helper.from_array(cent_t, name="centroids_T"),
        numpy_helper.from_array(cent_norm, name="centroid_norm"),
        numpy_helper.from_array(np.array([2], dtype=np.int64), name="axes_ch"),
        numpy_helper.from_array(np.array([-1], dtype=np.int64), name="axes_k"),
        numpy_helper.from_array(np.array([3], dtype=np.int64), name="axes_unsq"),
        numpy_helper.from_array(np.array([2], dtype=np.int64), name="axes_reduce_neighbors"),
        numpy_helper.from_array(np.array([topk], dtype=np.int64), name="topk_k"),
        numpy_helper.from_array(np.array(-2.0, dtype=np.float32), name="minus_two"),
        numpy_helper.from_array(np.array(1e-5, dtype=np.float32), name="eps"),
    ]

    nodes = [
        helper.make_node("Mul", ["feats", "feats"], ["x_sq"]),
        helper.make_node("ReduceSum", ["x_sq", "axes_ch"], ["x_norm"], keepdims=1),
        helper.make_node("MatMul", ["feats", "centroids_T"], ["dot"]),
        helper.make_node("Mul", ["dot", "minus_two"], ["minus2dot"]),
        helper.make_node("Add", ["x_norm", "centroid_norm"], ["norm_sum"]),
        helper.make_node("Add", ["norm_sum", "minus2dot"], ["dist_raw"]),
        helper.make_node("Max", ["dist_raw", "eps"], ["dist"]),
        helper.make_node("TopK", ["dist", "topk_k"], ["top_dist", "top_idx"], axis=-1, largest=0, sorted=1),
        helper.make_node("Reciprocal", ["top_dist"], ["inv"]),
        helper.make_node("Mul", ["inv", "inv"], ["w2"]),
        helper.make_node("ReduceSum", ["w2", "axes_k"], ["w_sum"], keepdims=1),
        helper.make_node("Div", ["w2", "w_sum"], ["weights"]),
        helper.make_node("Gather", ["centroids", "top_idx"], ["neighbors"], axis=0),
        helper.make_node("Unsqueeze", ["weights", "axes_unsq"], ["weights4"]),
        helper.make_node("Mul", ["neighbors", "weights4"], ["weighted_neighbors"]),
        helper.make_node("ReduceSum", ["weighted_neighbors", "axes_reduce_neighbors"], ["retrieved"], keepdims=0),
    ]

    graph = helper.make_graph(nodes, "A26_RVC_Retrieval_p249", inputs, outputs, initializers)
    model = helper.make_model(
        graph,
        producer_name="A26 FemaleVoice",
        opset_imports=[helper.make_operatorsetid("", 17)],
    )
    model.ir_version = min(model.ir_version, 10)
    onnx.checker.check_model(model)
    onnx.save(model, output)
    print(f"saved {output}: {Path(output).stat().st_size} bytes, centroids={k_centroids}, topk={topk}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--index", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--topk", type=int, default=8)
    args = ap.parse_args()
    centroids = extract_ivf_centroids(args.index)
    make_model(centroids, args.output, args.topk)


if __name__ == "__main__":
    main()
