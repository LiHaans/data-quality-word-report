# Kubernetes 集群恢复 Admin kubeconfig（RKE / Rancher 2.3）

适用场景：

- Rancher 2.3 删除后 kubeconfig 丢失
- kubectl 无法使用
- 集群仍然存活
- `/etc/kubernetes/ssl` 证书仍存在
- 需要重新生成 cluster-admin kubeconfig

---

# 一、确认 API Server 正常

```bash
curl -k https://192.168.88.216:6443/healthz
```

正常返回：

```text
ok
```

如果返回：

```json
{
  "status": "Failure",
  "reason": "Unauthorized",
  "code": 401
}
```

说明：

- kube-apiserver 正常
- 当前没有有效认证身份
- 可以继续执行后续步骤恢复 admin 权限

---

# 二、生成 Kubernetes Admin Client Key

```bash
openssl genrsa -out /root/k8s-admin.key 2048
```

---

# 三、生成 CSR（Certificate Signing Request）

```bash
openssl req -new -key /root/k8s-admin.key \
  -out /root/k8s-admin.csr \
  -subj "/CN=kubernetes-admin/O=system:masters"
```

说明：

- `CN=kubernetes-admin`
  - Kubernetes 用户名

- `O=system:masters`
  - Kubernetes 超级管理员组（cluster-admin）

---

# 四、使用 Kubernetes CA 签发 Admin 证书

```bash
openssl x509 -req \
  -in /root/k8s-admin.csr \
  -CA /etc/kubernetes/ssl/kube-ca.pem \
  -CAkey /etc/kubernetes/ssl/kube-ca-key.pem \
  -CAcreateserial \
  -out /root/k8s-admin.crt \
  -days 3650
```

生成文件：

```text
/root/k8s-admin.crt
```

说明：

- 该证书具备 Kubernetes cluster-admin 权限
- 有效期 3650 天（10 年）

---

# 五、生成 kubeconfig 文件

创建：

```bash
cat > /root/admin.kubeconfig << EOF
apiVersion: v1
kind: Config

clusters:
- cluster:
    certificate-authority: /etc/kubernetes/ssl/kube-ca.pem
    server: https://192.168.88.216:6443
  name: kubernetes

users:
- name: admin
  user:
    client-certificate: /root/k8s-admin.crt
    client-key: /root/k8s-admin.key

contexts:
- context:
    cluster: kubernetes
    user: admin
  name: admin

current-context: admin
EOF
```

---

# 六、启用 kubeconfig

```bash
export KUBECONFIG=/root/admin.kubeconfig
```

永久生效：

```bash
echo 'export KUBECONFIG=/root/admin.kubeconfig' >> ~/.bashrc
source ~/.bashrc
```

---

# 七、验证权限

```bash
kubectl get nodes
```

正常输出示例：

```text
NAME      STATUS   ROLES
node216   Ready    etcd
node217   Ready    etcd
node218   Ready    etcd
```

---

# 八、验证 cluster-admin 权限

```bash
kubectl auth can-i '*' '*' --all-namespaces
```

正常返回：

```text
yes
```

---

# 九、适用环境

本方案适用于：

- Rancher 2.x
- RKE1
- Kubernetes 使用 x509 client auth
- `/etc/kubernetes/ssl` 证书仍存在

---

# 十三、关键恢复原理

恢复核心依赖：

```text
kube-ca.pem
kube-ca-key.pem
```

通过 Kubernetes CA：

- 重新签发 system:masters 证书
- 恢复 cluster-admin 身份
- 重建 kubeconfig

无需重装集群。