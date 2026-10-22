import axios, { type AxiosInstance } from 'axios'
import { ElMessage } from 'element-plus'

/**
 * §5 统一响应包装 { code, message, data }
 * code !== 0 视为业务错误
 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}

const http: AxiosInstance = axios.create({
  baseURL: '',
  withCredentials: true,
  timeout: 60000
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data as ApiResponse<unknown>
    // 文件流（blob）直接返回
    if (body === undefined || body === null || typeof body !== 'object') {
      return resp
    }
    if (typeof body.code === 'number' && body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message))
    }
    return resp
  },
  (err) => {
    const resp = err.response
    if (resp) {
      if (resp.status === 401) {
        ElMessage.error('未登录或会话已过期')
        if (location.pathname !== '/login') {
          location.href = '/login'
        }
      } else if (resp.status === 403) {
        ElMessage.error('无权限执行该操作')
      } else if (resp.status === 429) {
        ElMessage.error('请求过于频繁')
      } else {
        ElMessage.error(resp.data?.message || `请求失败（${resp.status}）`)
      }
    } else {
      ElMessage.error('网络异常，请稍后重试')
    }
    return Promise.reject(err)
  }
)

/** 解包 data 字段 */
async function unwrap<T>(p: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  const r = await p
  return r.data.data
}

export const api = {
  get: <T>(url: string, params?: Record<string, unknown>) =>
    unwrap<T>(http.get(url, { params })),
  post: <T>(url: string, data?: unknown) => unwrap<T>(http.post(url, data)),
  put: <T>(url: string, data?: unknown) => unwrap<T>(http.put(url, data)),
  del: <T>(url: string, data?: unknown) => unwrap<T>(http.delete(url, { data })),
  /** 下载二进制 */
  download: async (url: string, params?: Record<string, unknown>) => {
    const r = await http.get(url, { params, responseType: 'blob' })
    return r.data as Blob
  },
  /** 上传 multipart；timeout 可按接口覆盖（备份恢复可能跑很久） */
  upload: async <T>(url: string, form: FormData, timeout = 60000) => {
    const r = await http.post(url, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout
    })
    return r.data.data as T
  }
}

export function saveBlob(blob: Blob, filename: string) {
  const a = document.createElement('a')
  const url = URL.createObjectURL(blob)
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

export default http
