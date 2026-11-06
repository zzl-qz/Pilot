/** 对照 MobileConnectionService 环境响应和 Kotlin PairingAdmin/PairingNetwork 的 JSON。 */
export interface MobileNetwork {
  connectionMode?: string; networkState?: string; networkMessage?: string;
  networkEnvironment?: { message: string; availability?: string; interfaceCount?: number; ipv4?: boolean; ipv6?: boolean; tunnelHint?: boolean; proxyTypes?: string[]; proxyDetection?: string; invalidProxy?: boolean; explicitProxySupported?: boolean } | null;
}
export interface MobileDevice { id: string; name: string; pairedAt: number; online: boolean }
export interface MobileInvite extends MobileNetwork { code: string; image: string; expiresAt: number; expiresIn?: number; name?: string; development?: boolean }
/** 环境尚未准备好时仅有 version、running 和 environment，不能要求设备列表已存在。 */
export interface MobileStatus extends MobileNetwork {
  version?: number; running?: boolean; environment?: { state: string; message: string };
  maxDevices?: number; name?: string; pairedDevice?: MobileDevice | null; pairingState?: string;
  invite?: MobileInvite | null; devices?: MobileDevice[];
}
