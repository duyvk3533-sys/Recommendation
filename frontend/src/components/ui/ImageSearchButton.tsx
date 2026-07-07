import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent } from 'react';
import { Camera, Loader2, X } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { productService } from '../../api/productService';
import type { Product } from '../../types';

interface ImageSearchButtonProps {
  topK?: number;
  onResults?: (products: Product[]) => void;
  onLoadingChange?: (loading: boolean) => void;
  onError?: (message: string) => void;
  className?: string;
}

const getErrorMessage = (error: unknown): string => {
  if (error instanceof Error && error.message) {
    return error.message;
  }

  if (typeof error === 'object' && error !== null && 'response' in error) {
    const response = (error as { response?: { data?: { message?: string } | string } }).response;

    if (typeof response?.data === 'string') {
      return response.data;
    }

    if (response?.data?.message) {
      return response.data.message;
    }
  }

  return 'Không thể tìm kiếm bằng hình ảnh. Vui lòng thử lại.';
};

export function ImageSearchButton({
  topK = 10,
  onResults,
  onLoadingChange,
  onError,
  className = ''
}: ImageSearchButtonProps) {
  const navigate = useNavigate();
  const inputRef = useRef<HTMLInputElement | null>(null);
  const objectUrlRef = useRef<string | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const updateLoading = (nextLoading: boolean) => {
    setLoading(nextLoading);
    onLoadingChange?.(nextLoading);
  };

  const clearPreview = () => {
    if (objectUrlRef.current) {
      URL.revokeObjectURL(objectUrlRef.current);
      objectUrlRef.current = null;
    }

    setPreviewUrl(null);

    if (inputRef.current) {
      inputRef.current.value = '';
    }
  };

  useEffect(() => {
    return () => {
      if (objectUrlRef.current) {
        URL.revokeObjectURL(objectUrlRef.current);
      }
    };
  }, []);

  const handleOpenPicker = () => {
    if (!loading) {
      inputRef.current?.click();
    }
  };

  const handleFileChange = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];

    if (!file) {
      return;
    }

    clearPreview();

    const nextPreviewUrl = URL.createObjectURL(file);
    objectUrlRef.current = nextPreviewUrl;
    setPreviewUrl(nextPreviewUrl);
    updateLoading(true);

    try {
      const products = await productService.searchByImage(file, topK);

      if (onResults) {
        onResults(products);
      } else {
        navigate('/search?mode=image', {
          state: {
            imageResults: products,
            previewUrl: nextPreviewUrl
          }
        });
      }
    } catch (error) {
      onError?.(getErrorMessage(error));
    } finally {
      updateLoading(false);
    }
  };

  return (
    <div className={`relative inline-flex items-center ${className}`}>
      <input
        ref={inputRef}
        type="file"
        accept="image/*"
        className="hidden"
        onChange={handleFileChange}
      />

      <button
        type="button"
        onClick={handleOpenPicker}
        disabled={loading}
        className="inline-flex items-center justify-center rounded-full bg-pink-600 p-3 text-white shadow-md transition hover:bg-pink-700 disabled:cursor-not-allowed disabled:opacity-70"
        aria-label="Tìm kiếm bằng hình ảnh"
      >
        {loading ? <Loader2 className="h-5 w-5 animate-spin" /> : <Camera className="h-5 w-5" />}
      </button>

      {previewUrl && (
        <div className="ml-3 inline-flex items-center gap-2 rounded-full border border-pink-100 bg-white px-2 py-1 shadow-sm">
          <img
            src={previewUrl}
            alt="Ảnh xem trước"
            className="h-10 w-10 rounded-full object-cover"
          />
          <button
            type="button"
            onClick={clearPreview}
            disabled={loading}
            className="inline-flex h-7 w-7 items-center justify-center rounded-full text-gray-500 transition hover:bg-gray-100 hover:text-gray-700 disabled:cursor-not-allowed disabled:opacity-60"
            aria-label="Xóa ảnh đã chọn"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
      )}
    </div>
  );
}

export default ImageSearchButton;
