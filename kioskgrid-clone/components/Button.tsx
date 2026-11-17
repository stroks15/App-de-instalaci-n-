import React from 'react';

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'ghost' | 'danger';
}

export const Button: React.FC<ButtonProps> = ({ variant = 'primary', ...props }) => {
  const baseStyle = "px-4 py-2 rounded-md font-medium transition-colors";
  const variants = {
    primary: "bg-blue-600 text-white hover:bg-blue-700",
    ghost: "bg-transparent text-gray-600 hover:bg-gray-100",
    danger: "bg-red-600 text-white hover:bg-red-700"
  };

  return (
    <button className={`${baseStyle} ${variants[variant]}`} {...props} />
  );
};
